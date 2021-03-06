/*
 * Copyright (c) 2011-2021, baomidou (jobob@qq.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baomidou.mybatisplus.extension.plugins.inner;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.DialectFactory;
import com.baomidou.mybatisplus.extension.plugins.pagination.DialectModel;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.IDialect;
import com.baomidou.mybatisplus.extension.toolkit.JdbcUtils;
import com.baomidou.mybatisplus.extension.toolkit.PropertyMapper;
import com.baomidou.mybatisplus.extension.toolkit.SqlParserUtils;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ???????????????
 * <p>
 * ????????? left join ????????????,???????????????count,??????????????????????????????1??????????????????????????????????????????
 *
 * @author hubin
 * @since 3.4.0
 */
@Data
@NoArgsConstructor
@SuppressWarnings({"rawtypes"})
public class PaginationInnerInterceptor implements InnerInterceptor {

    protected static final List<SelectItem> COUNT_SELECT_ITEM = Collections.singletonList(defaultCountSelectItem());
    protected static final Map<String, MappedStatement> countMsCache = new ConcurrentHashMap<>();
    protected final Log logger = LogFactory.getLog(this.getClass());

    /**
     * ??????jsqlparser???count???SelectItem
     */
    private static SelectItem defaultCountSelectItem() {
        Function function = new Function();
        function.setName("COUNT");
        function.setAllColumns(true);
        return new SelectExpressionItem(function);
    }

    /**
     * ????????????????????????????????????
     */
    protected boolean overflow;
    /**
     * ????????????????????????
     */
    protected Long maxLimit;
    /**
     * ???????????????
     * <p>
     * ?????? {@link #findIDialect(Executor)} ??????
     */
    private DbType dbType;
    /**
     * ???????????????
     * <p>
     * ?????? {@link #findIDialect(Executor)} ??????
     */
    private IDialect dialect;
    /**
     * ?????? countSql ????????? join
     * ??????????????? left join
     *
     * @since 3.4.2
     */
    protected boolean optimizeJoin = true;

    public PaginationInnerInterceptor(DbType dbType) {
        this.dbType = dbType;
    }

    public PaginationInnerInterceptor(IDialect dialect) {
        this.dialect = dialect;
    }

    /**
     * ????????????count,??????count???0?????????false(??????????????????sql???)
     */
    @Override
    public boolean willDoQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        IPage<?> page = ParameterUtils.findPage(parameter).orElse(null);
        if (page == null || page.getSize() < 0 || !page.searchCount()) {
            return true;
        }

        BoundSql countSql;
        MappedStatement countMs = buildCountMappedStatement(ms, page.countId());
        if (countMs != null) {
            countSql = countMs.getBoundSql(parameter);
        } else {
            countMs = buildAutoCountMappedStatement(ms);
            String countSqlStr = autoCountSql(page.optimizeCountSql(), boundSql.getSql());
            PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);
            countSql = new BoundSql(countMs.getConfiguration(), countSqlStr, mpBoundSql.parameterMappings(), parameter);
            PluginUtils.setAdditionalParameter(countSql, mpBoundSql.additionalParameters());
        }

        CacheKey cacheKey = executor.createCacheKey(countMs, parameter, rowBounds, countSql);
        List<Object> result = executor.query(countMs, parameter, rowBounds, resultHandler, cacheKey, countSql);
        long total = 0;
        if (CollectionUtils.isNotEmpty(result)) {
            // ??????????????? count ????????????????????? 0
            Object o = result.get(0);
            if (o != null) {
                total = Long.parseLong(o.toString());
            }
        }
        page.setTotal(total);
        return continuePage(page);
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        IPage<?> page = ParameterUtils.findPage(parameter).orElse(null);
        if (null == page) {
            return;
        }

        // ?????? orderBy ??????
        boolean addOrdered = false;
        String buildSql = boundSql.getSql();
        List<OrderItem> orders = page.orders();
        if (!CollectionUtils.isEmpty(orders)) {
            addOrdered = true;
            buildSql = this.concatOrderBy(buildSql, orders);
        }

        // size ?????? 0 ???????????????sql
        if (page.getSize() < 0) {
            if (addOrdered) {
                PluginUtils.mpBoundSql(boundSql).sql(buildSql);
            }
            return;
        }

        handlerLimit(page);
        IDialect dialect = findIDialect(executor);

        final Configuration configuration = ms.getConfiguration();
        DialectModel model = dialect.buildPaginationSql(buildSql, page.offset(), page.getSize());
        PluginUtils.MPBoundSql mpBoundSql = PluginUtils.mpBoundSql(boundSql);

        List<ParameterMapping> mappings = mpBoundSql.parameterMappings();
        Map<String, Object> additionalParameter = mpBoundSql.additionalParameters();
        model.consumers(mappings, configuration, additionalParameter);
        mpBoundSql.sql(model.getDialectSql());
        mpBoundSql.parameterMappings(mappings);
    }

    /**
     * ??????????????????????????????
     *
     * @param executor Executor
     * @return ???????????????
     */
    protected IDialect findIDialect(Executor executor) {
        if (dialect != null) {
            return dialect;
        }
        if (dbType != null) {
            dialect = DialectFactory.getDialect(dbType);
            return dialect;
        }
        return DialectFactory.getDialect(JdbcUtils.getDbType(executor));
    }

    /**
     * ??????????????? id ??? MappedStatement
     *
     * @param ms      MappedStatement
     * @param countId id
     * @return MappedStatement
     */
    protected MappedStatement buildCountMappedStatement(MappedStatement ms, String countId) {
        if (StringUtils.isNotBlank(countId)) {
            final String id = ms.getId();
            if (!countId.contains(StringPool.DOT)) {
                countId = id.substring(0, id.lastIndexOf(StringPool.DOT) + 1) + countId;
            }
            final Configuration configuration = ms.getConfiguration();
            try {
                return CollectionUtils.computeIfAbsent(countMsCache, countId, key -> configuration.getMappedStatement(key, false));
            } catch (Exception e) {
                logger.warn(String.format("can not find this countId: [\"%s\"]", countId));
            }
        }
        return null;
    }

    /**
     * ?????? mp ??????????????? MappedStatement
     *
     * @param ms MappedStatement
     * @return MappedStatement
     */
    protected MappedStatement buildAutoCountMappedStatement(MappedStatement ms) {
        final String countId = ms.getId() + "_mpCount";
        final Configuration configuration = ms.getConfiguration();
        return CollectionUtils.computeIfAbsent(countMsCache, countId, key -> {
            MappedStatement.Builder builder = new MappedStatement.Builder(configuration, key, ms.getSqlSource(), ms.getSqlCommandType());
            builder.resource(ms.getResource());
            builder.fetchSize(ms.getFetchSize());
            builder.statementType(ms.getStatementType());
            builder.timeout(ms.getTimeout());
            builder.parameterMap(ms.getParameterMap());
            builder.resultMaps(Collections.singletonList(new ResultMap.Builder(configuration, Constants.MYBATIS_PLUS, Long.class, Collections.emptyList()).build()));
            builder.resultSetType(ms.getResultSetType());
            builder.cache(ms.getCache());
            builder.flushCacheRequired(ms.isFlushCacheRequired());
            builder.useCache(ms.isUseCache());
            return builder.build();
        });
    }

    /**
     * ????????????????????? countSql
     *
     * @param optimizeCountSql ??????????????????
     * @param sql              sql
     * @return countSql
     */
    protected String autoCountSql(boolean optimizeCountSql, String sql) {
        if (!optimizeCountSql) {
            return lowLevelCountSql(sql);
        }
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
            Distinct distinct = plainSelect.getDistinct();
            GroupByElement groupBy = plainSelect.getGroupBy();
            List<OrderByElement> orderBy = plainSelect.getOrderByElements();

            if (CollectionUtils.isNotEmpty(orderBy)) {
                boolean canClean = true;
                if (groupBy != null) {
                    // ??????groupBy ?????????orderBy
                    canClean = false;
                }
                if (canClean) {
                    for (OrderByElement order : orderBy) {
                        // order by ????????????,?????????order by
                        Expression expression = order.getExpression();
                        if (!(expression instanceof Column) && expression.toString().contains(StringPool.QUESTION_MARK)) {
                            canClean = false;
                            break;
                        }
                    }
                }
                if (canClean) {
                    plainSelect.setOrderByElements(null);
                }
            }
            //#95 Github, selectItems contains #{} ${}, which will be translated to ?, and it may be in a function: power(#{myInt},2)
            for (SelectItem item : plainSelect.getSelectItems()) {
                if (item.toString().contains(StringPool.QUESTION_MARK)) {
                    return lowLevelCountSql(select.toString());
                }
            }
            // ?????? distinct???groupBy?????????
            if (distinct != null || null != groupBy) {
                return lowLevelCountSql(select.toString());
            }
            // ?????? join ??????,???????????????????????? join ??????
            if (optimizeJoin) {
                List<Join> joins = plainSelect.getJoins();
                if (CollectionUtils.isNotEmpty(joins)) {
                    boolean canRemoveJoin = true;
                    String whereS = Optional.ofNullable(plainSelect.getWhere()).map(Expression::toString).orElse(StringPool.EMPTY);
                    // ??????????????????
                    whereS = whereS.toLowerCase();
                    for (Join join : joins) {
                        if (!join.isLeft()) {
                            canRemoveJoin = false;
                            break;
                        }
                        FromItem rightItem = join.getRightItem();
                        String str = "";
                        if (rightItem instanceof Table) {
                            Table table = (Table) rightItem;
                            str = Optional.ofNullable(table.getAlias()).map(Alias::getName).orElse(table.getName()) + StringPool.DOT;
                        } else if (rightItem instanceof SubSelect) {
                            SubSelect subSelect = (SubSelect) rightItem;
                            /* ?????? left join ??????????????????????????????????????? ?(???????????????) ?????? where ????????????????????? join ????????????????????????,???????????? join */
                            if (subSelect.toString().contains(StringPool.QUESTION_MARK)) {
                                canRemoveJoin = false;
                                break;
                            }
                            str = subSelect.getAlias().getName() + StringPool.DOT;
                        }
                        // ??????????????????
                        str = str.toLowerCase();
                        String onExpressionS = join.getOnExpression().toString();
                        /* ?????? join ????????? ?(???????????????) ?????? where ????????????????????? join ????????????????????????,???????????? join */
                        if (onExpressionS.contains(StringPool.QUESTION_MARK) || whereS.contains(str)) {
                            canRemoveJoin = false;
                            break;
                        }
                    }
                    if (canRemoveJoin) {
                        plainSelect.setJoins(null);
                    }
                }
            }
            // ?????? SQL
            plainSelect.setSelectItems(COUNT_SELECT_ITEM);
            return select.toString();
        } catch (JSQLParserException e) {
            // ????????????????????? SQL
            logger.warn("optimize this sql to a count sql has exception, sql:\"" + sql + "\", exception:\n" + e.getCause());
        } catch (Exception e) {
            logger.warn("optimize this sql to a count sql has error, sql:\"" + sql + "\", exception:\n" + e);
        }
        return lowLevelCountSql(sql);
    }

    /**
     * ????????????count?????????,?????????????????????
     *
     * @param originalSql ??????sql
     * @return countSql
     */
    protected String lowLevelCountSql(String originalSql) {
        return SqlParserUtils.getOriginalCountSql(originalSql);
    }

    /**
     * ??????SQL??????Order By
     *
     * @param originalSql ???????????????SQL
     * @return ignore
     */
    public String concatOrderBy(String originalSql, List<OrderItem> orderList) {
        try {
            Select select = (Select) CCJSqlParserUtil.parse(originalSql);
            SelectBody selectBody = select.getSelectBody();
            if (selectBody instanceof PlainSelect) {
                PlainSelect plainSelect = (PlainSelect) selectBody;
                List<OrderByElement> orderByElements = plainSelect.getOrderByElements();
                List<OrderByElement> orderByElementsReturn = addOrderByElements(orderList, orderByElements);
                plainSelect.setOrderByElements(orderByElementsReturn);
                return select.toString();
            } else if (selectBody instanceof SetOperationList) {
                SetOperationList setOperationList = (SetOperationList) selectBody;
                List<OrderByElement> orderByElements = setOperationList.getOrderByElements();
                List<OrderByElement> orderByElementsReturn = addOrderByElements(orderList, orderByElements);
                setOperationList.setOrderByElements(orderByElementsReturn);
                return select.toString();
            } else if (selectBody instanceof WithItem) {
                // todo: don't known how to resole
                return originalSql;
            } else {
                return originalSql;
            }
        } catch (JSQLParserException e) {
            logger.warn("failed to concat orderBy from IPage, exception:\n" + e.getCause());
        } catch (Exception e) {
            logger.warn("failed to concat orderBy from IPage, exception:\n" + e);
        }
        return originalSql;
    }

    protected List<OrderByElement> addOrderByElements(List<OrderItem> orderList, List<OrderByElement> orderByElements) {
        List<OrderByElement> additionalOrderBy = orderList.stream()
            .filter(item -> StringUtils.isNotBlank(item.getColumn()))
            .map(item -> {
                OrderByElement element = new OrderByElement();
                element.setExpression(new Column(item.getColumn()));
                element.setAsc(item.isAsc());
                element.setAscDescPresent(true);
                return element;
            }).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(orderByElements)) {
            return additionalOrderBy;
        }
        orderByElements.addAll(additionalOrderBy);
        return orderByElements;
    }

    /**
     * count ????????????,????????????????????????
     *
     * @param page ????????????
     * @return ??????
     */
    protected boolean continuePage(IPage<?> page) {
        if (page.getTotal() <= 0) {
            return false;
        }
        if (page.getCurrent() > page.getPages()) {
            if (overflow) {
                //?????????????????????
                handlerOverflow(page);
            } else {
                // ???????????????????????????????????????????????? list ??????
                return false;
            }
        }
        return true;
    }

    /**
     * ??????????????????????????????,?????????????????????
     *
     * @param page IPage
     */
    protected void handlerLimit(IPage<?> page) {
        final long size = page.getSize();
        Long pageMaxLimit = page.maxLimit();
        Long limit = pageMaxLimit != null ? pageMaxLimit : maxLimit;
        if (limit != null && limit > 0 && size > limit) {
            page.setSize(limit);
        }
    }

    /**
     * ??????????????????,????????????????????????
     *
     * @param page IPage
     */
    protected void handlerOverflow(IPage<?> page) {
        page.setCurrent(1);
    }

    @Override
    public void setProperties(Properties properties) {
        PropertyMapper.newInstance(properties)
            .whenNotBlack("overflow", Boolean::parseBoolean, this::setOverflow)
            .whenNotBlack("dbType", DbType::getDbType, this::setDbType)
            .whenNotBlack("dialect", ClassUtils::newInstance, this::setDialect)
            .whenNotBlack("maxLimit", Long::parseLong, this::setMaxLimit)
            .whenNotBlack("optimizeJoin", Boolean::parseBoolean, this::setOptimizeJoin);
    }
}
