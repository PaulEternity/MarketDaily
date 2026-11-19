package com.pauleternity.marketdaily.market;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 提取上海黄金交易所的日收盘价和延时最新价。 */
final class SgeDailyTable {

    /** 本地交易日期及收盘价，报价单位由合约定义。 */
    record Close(LocalDate date, BigDecimal price) {
    }

    /** 为每个合约取最近两个不同交易日的有效收盘记录。 */
    static Map<String, List<Close>> recentCloses(String html, LocalDate through, Set<String> contracts) {
        var rows = parseRows(html, true);

        // 用表头名称定位字段，避免表格插入列时把其他价格误当作收盘价。
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<String> header = rows.getFirst();
        int dateIndex = header.indexOf("日期");
        int contractIndex = header.indexOf("合约");
        int closeIndex = header.indexOf("收盘价");
        if (dateIndex < 0 || contractIndex < 0 || closeIndex < 0) {
            return Map.of();
        }

        // 按合约分别取交易日倒序结果，避免把金银同日记录互相覆盖。
        var closes = new HashMap<String, TreeMap<LocalDate, BigDecimal>>();
        int lastIndex = Math.max(dateIndex, Math.max(contractIndex, closeIndex));
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() <= lastIndex || !contracts.contains(row.get(contractIndex))) {
                continue;
            }
            try {
                LocalDate date = LocalDate.parse(row.get(dateIndex));
                BigDecimal price = new BigDecimal(row.get(closeIndex).replace(",", ""));
                if (!date.isAfter(through) && price.signum() > 0) {
                    closes.computeIfAbsent(row.get(contractIndex), ignored -> new TreeMap<>(Comparator.reverseOrder()))
                            .putIfAbsent(date, price);
                }
            } catch (DateTimeParseException | NumberFormatException ignored) {
                // 休市或无成交行不参与收盘比较。
            }
        }
        var result = new HashMap<String, List<Close>>();
        closes.forEach((contract, prices) -> result.put(contract, prices.entrySet().stream().limit(2)
                .map(e -> new Close(e.getKey(), e.getValue())).toList()));
        return result;
    }

    /** 从延时报价的表头定位合约和最新价列，过滤无效及未开盘报价。 */
    static Map<String, BigDecimal> delayedPrices(String html, Set<String> contracts) {
        var rows = parseRows(html, false);
        // 延时表由“合约”和“最新价”两列识别，其他表不参与解析。
        var header = rows.stream().filter(row -> row.contains("合约") && row.contains("最新价")).findFirst();
        if (header.isEmpty()) {
            return Map.of();
        }
        int contractIndex = header.get().indexOf("合约");
        int priceIndex = header.get().indexOf("最新价");
        var prices = new HashMap<String, BigDecimal>();
        for (var row : rows.subList(rows.indexOf(header.get()) + 1, rows.size())) {
            // 表尾之后的布局表即使含有合约名称，也不属于延时报价。
            if (row.isEmpty()) {
                break;
            }
            if (row.size() <= Math.max(contractIndex, priceIndex) || !contracts.contains(row.get(contractIndex))) {
                continue;
            }
            try {
                BigDecimal price = new BigDecimal(row.get(priceIndex).replace(",", ""));
                if (price.signum() > 0) {
                    prices.putIfAbsent(row.get(contractIndex), price);
                }
            } catch (NumberFormatException ignored) {
                // 未成交和缺失字段留空。
            }
        }
        return prices;
    }

    /** 共享 HTML 单元格读取器，日行情按样式类过滤，延时报价按表头定位。 */
    private static List<List<String>> parseRows(String html, boolean dailyOnly) {
        var callback = new QuoteRows(dailyOnly);
        try {
            new ParserDelegator().parse(new StringReader(html), callback, true);
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取上金所行情");
        }
        return callback.rows;
    }

    /** 保存行情表的行与单元格文本。 */
    private static final class QuoteRows extends HTMLEditorKit.ParserCallback {
        // 日行情页另含布局表，只读取带行情样式类的表格。
        private final boolean dailyOnly;
        private final List<List<String>> rows = new ArrayList<>();
        private boolean inTable;
        private List<String> row;
        private StringBuilder cell;

        /** 记录是否需要按日行情样式类过滤。 */
        QuoteRows(boolean dailyOnly) {
            this.dailyOnly = dailyOnly;
        }

        /** 在目标表内开始新的一行或一个单元格。 */
        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
            if (tag == HTML.Tag.TABLE) {
                inTable = !dailyOnly || "daily_new_table".equals(attrs.getAttribute(HTML.Attribute.CLASS));
            } else if (inTable && tag == HTML.Tag.TR) {
                row = new ArrayList<>();
            } else if (inTable && row != null && (tag == HTML.Tag.TD || tag == HTML.Tag.TH)) {
                cell = new StringBuilder();
            }
        }

        /** 保存单元格文本；嵌套标签不改变报价字段。 */
        @Override
        public void handleText(char[] data, int pos) {
            if (cell != null) {
                cell.append(data);
            }
        }

        /** 完成当前字段或整行，离开目标表后停止记录。 */
        @Override
        public void handleEndTag(HTML.Tag tag, int pos) {
            if ((tag == HTML.Tag.TD || tag == HTML.Tag.TH) && cell != null && row != null) {
                row.add(cell.toString().strip());
                cell = null;
            } else if (tag == HTML.Tag.TR && row != null) {
                if (dailyOnly || !row.isEmpty()) {
                    rows.add(row);
                }
                row = null;
            } else if (tag == HTML.Tag.TABLE) {
                if (!dailyOnly && inTable) {
                    rows.add(List.of());
                }
                inTable = false;
            }
        }
    }
}
