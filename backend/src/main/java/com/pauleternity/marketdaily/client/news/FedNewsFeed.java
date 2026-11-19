package com.pauleternity.marketdaily.client.news;

import com.pauleternity.marketdaily.shared.SourceHttp;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** 读取美联储官方货币政策公告 RSS。 */
@Component
class FedNewsFeed implements NewsFeed {

    private final SourceHttp http;

    /** 使用统一 HTTP 客户端抓取 RSS。 */
    FedNewsFeed(SourceHttp http) {
        this.http = http;
    }

    /** 美联储新闻数据源名称。 */
    @Override
    public String source() {
        return "FED_RSS";
    }

    /** RSS 由上游一次返回，时间范围由清洗层统一应用。 */
    @Override
    public List<Item> fetch(int hours) {
        return parse(http.readBody(http.get().uri("https://www.federalreserve.gov/feeds/press_monetary.xml")));
    }

    /** 只解析 RSS 元数据，拒绝 DTD 与外部实体。 */
    List<Item> parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // HTTP 解码后的 UTF-8 BOM 会留在字符串开头，字符流解析前需移除。
            String content = xml.startsWith("\uFEFF") ? xml.substring(1) : xml;
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
            if (!"rss".equals(document.getDocumentElement().getTagName())) {
                throw new IllegalStateException("美联储响应不是 RSS");
            }
            var entries = document.getElementsByTagName("item");
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                items.add(new Item(text(entry, "title"), text(entry, "link"), "en",
                        text(entry, "description"), publishedAt(text(entry, "pubDate")), null));
            }
            return items;
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw new IllegalStateException("美联储 RSS 无法解析");
        }
    }

    /** 读取条目第一个同名字段，无字段时返回空串。 */
    private String text(Element node, String tag) {
        var value = node.getElementsByTagName(tag).item(0);
        return value == null ? "" : value.getTextContent();
    }

    /** 将 RSS 自带时区的发布时间转为 UTC。 */
    private Instant publishedAt(String value) {
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
