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
import java.util.List;
import java.util.TreeMap;

/** 提取上海黄金交易所每日行情表的 Au99.99 收盘价。 */
final class SgeDailyTable {

    /** 交易日当地日期及人民币每克收盘价。 */
    record Close(LocalDate date, BigDecimal price) {
    }

    /** 仅从官方每日行情表取最后两条可用的不同交易日收盘记录。 */
    static List<Close> recentCloses(String html, LocalDate through) {
        var callback = new DailyRows();
        try {
            new ParserDelegator().parse(new StringReader(html), callback, true);
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取上金所每日行情");
        }

        // 用表头名称定位字段，避免表格插入列时把其他价格误当作收盘价。
        if (callback.rows.isEmpty()) {
            return List.of();
        }
        List<String> header = callback.rows.getFirst();
        int dateIndex = header.indexOf("日期");
        int contractIndex = header.indexOf("合约");
        int closeIndex = header.indexOf("收盘价");
        if (dateIndex < 0 || contractIndex < 0 || closeIndex < 0) {
            return List.of();
        }

        // 交易日倒序，按日期去重，即使上游顺序变化也不影响涨跌比较。
        var closes = new TreeMap<LocalDate, BigDecimal>(Comparator.reverseOrder());
        int lastIndex = Math.max(dateIndex, Math.max(contractIndex, closeIndex));
        for (List<String> row : callback.rows.subList(1, callback.rows.size())) {
            if (row.size() <= lastIndex || !"Au99.99".equals(row.get(contractIndex))) {
                continue;
            }
            try {
                LocalDate date = LocalDate.parse(row.get(dateIndex));
                BigDecimal price = new BigDecimal(row.get(closeIndex).replace(",", ""));
                if (!date.isAfter(through) && price.signum() > 0) {
                    closes.putIfAbsent(date, price);
                }
            } catch (DateTimeParseException | NumberFormatException ignored) {
                // 休市或无成交行不参与收盘比较。
            }
        }
        return closes.entrySet().stream().limit(2).map(e -> new Close(e.getKey(), e.getValue())).toList();
    }

    /** 只记录带 daily_new_table 标记的目标表格，忽略其他布局表。 */
    private static final class DailyRows extends HTMLEditorKit.ParserCallback {
        private final List<List<String>> rows = new ArrayList<>();
        private boolean inTable;
        private List<String> row;
        private StringBuilder cell;

        /** 在目标表内开始新的一行或一个单元格。 */
        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
            if (tag == HTML.Tag.TABLE) {
                inTable = "daily_new_table".equals(attrs.getAttribute(HTML.Attribute.CLASS));
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
                rows.add(row);
                row = null;
            } else if (tag == HTML.Tag.TABLE) {
                inTable = false;
            }
        }
    }
}
