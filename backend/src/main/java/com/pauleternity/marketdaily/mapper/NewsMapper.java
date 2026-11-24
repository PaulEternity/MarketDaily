package com.pauleternity.marketdaily.mapper;

import com.pauleternity.marketdaily.model.news.NewsArticle;
import com.pauleternity.marketdaily.model.news.NewsEventMember;
import com.pauleternity.marketdaily.model.news.NewsMarket;
import com.pauleternity.marketdaily.model.news.NewsRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/** 新闻、事件和市场关系的 MyBatis 持久化接口。 */
@Mapper
public interface NewsMapper {

    // 市场筛选以事件为单位，选中任一关联市场可同时看到该事件的多个来源。
    String WINDOW = """
            FROM market_news n
            WHERE n.news_at BETWEEN #{from} AND #{through}
            <if test="market != null">
              AND EXISTS (
                SELECT 1 FROM market_news linked
                JOIN news_market_relation rel ON rel.news_id = linked.id
                WHERE linked.event_hash = n.event_hash AND rel.market_type = #{market}
              )
            </if>
            """;

    // 列顺序和 NewsRow 构造器一致。归并事件的全部市场标签用于前端筛选和展示。
    String ROW_COLUMNS = """
            n.id, n.title, n.url, n.source, n.language, n.short_description, n.published_at,
            n.source_seen_at, n.collected_at, n.event_hash, n.importance_score, n.is_selected,
            (SELECT GROUP_CONCAT(DISTINCT rel.market_type ORDER BY rel.market_type)
             FROM market_news linked JOIN news_market_relation rel ON rel.news_id = linked.id
             WHERE linked.event_hash = n.event_hash)
            """;

    /** 规范链接唯一，重复采集保留原有事件归属、首次采集时间和 AI 引用状态。 */
    @Insert("""
            INSERT INTO market_news
            (title, title_normalized, url, url_hash, source, language, short_description,
             published_at, source_seen_at, collected_at, event_hash, importance_score)
            VALUES (#{title}, #{titleNormalized}, #{url}, #{urlHash}, #{source}, #{language}, #{summary},
                    #{publishedAt}, #{seenAt}, #{collectedAt}, #{eventHash}, #{score}) AS incoming
            ON DUPLICATE KEY UPDATE
                language = COALESCE(market_news.language, incoming.language),
                short_description = COALESCE(market_news.short_description, incoming.short_description),
                published_at = COALESCE(market_news.published_at, incoming.published_at),
                source_seen_at = COALESCE(market_news.source_seen_at, incoming.source_seen_at),
                importance_score = GREATEST(market_news.importance_score, incoming.importance_score)
            """)
    void save(NewsArticle article);

    /** 读取已采集链接的稳定身份，以便补充市场关系。 */
    @Select("""
            SELECT id, title_normalized, news_at, event_hash FROM market_news WHERE url_hash = #{urlHash}
            """)
    NewsEventMember byUrl(@Param("urlHash") String urlHash);

    /** 只取本轮可能匹配的事件时间范围，优先匹配最早归档的事件。 */
    @Select("""
            SELECT id, title_normalized, news_at, event_hash FROM market_news
            WHERE news_at BETWEEN #{from} AND #{through}
            ORDER BY news_at, id
            """)
    List<NewsEventMember> candidates(@Param("from") Instant from, @Param("through") Instant through);

    /** 保证同一文章的同一市场只关联一次。 */
    @Insert("""
            INSERT INTO news_market_relation (news_id, market_type) VALUES (#{id}, #{market})
            ON DUPLICATE KEY UPDATE news_id = news_market_relation.news_id
            """)
    void saveMarket(@Param("id") long id, @Param("market") NewsMarket market);

    /**
     * 删除早于截止时间的报道，由外键级联清理其市场关系。
     * @param cutoff UTC 截止时间，缺少发布时间和来源收录时间的旧记录按入库时间判断
     * @return 删除的报道条数
     */
    @Delete("""
            DELETE FROM market_news
            WHERE news_at < #{cutoff}
               OR (news_at IS NULL AND collected_at < #{cutoff})
            """)
    int deleteExpired(@Param("cutoff") Instant cutoff);

    /** 按新闻实际时间窗口统计符合条件的文章数。 */
    @Select("<script>SELECT COUNT(*) " + WINDOW + "</script>")
    long count(@Param("from") Instant from, @Param("through") Instant through, @Param("market") NewsMarket market);

    /** 返回指定页的可追溯文章，分数相同以时间和 ID 保持稳定排序。 */
    @Select("""
            <script>
            SELECT
            """ + ROW_COLUMNS + WINDOW + """
            ORDER BY n.importance_score DESC, n.news_at DESC, n.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<NewsRow> page(@Param("from") Instant from, @Param("through") Instant through, @Param("market") NewsMarket market,
                   @Param("limit") int limit, @Param("offset") long offset);

    /** 全部报道按发布时间或收录时间倒序，保留同一事件的不同来源。 */
    @Select("""
            <script>
            SELECT
            """ + ROW_COLUMNS + WINDOW + """
            ORDER BY n.news_at DESC, n.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<NewsRow> recentPage(@Param("from") Instant from, @Param("through") Instant through,
                             @Param("market") NewsMarket market, @Param("limit") int limit, @Param("offset") long offset);

    /** 每个事件保留分数最高的报道，选出最重要的事件后按时间倒序显示。 */
    @Select("""
            <script>
            WITH ranked AS (
                SELECT n.id, n.importance_score, n.news_at, ROW_NUMBER() OVER (
                    PARTITION BY n.event_hash ORDER BY n.importance_score DESC, n.news_at DESC, n.id DESC
                ) AS event_rank
            """ + WINDOW + """
            ), picked AS (
                SELECT id FROM ranked WHERE event_rank = 1
                ORDER BY importance_score DESC, news_at DESC, id DESC LIMIT #{limit}
            )
            SELECT
            """ + ROW_COLUMNS + """
            FROM market_news n JOIN picked ON picked.id = n.id
            ORDER BY n.news_at DESC, n.id DESC
            </script>
            """)
    List<NewsRow> highlights(@Param("from") Instant from, @Param("through") Instant through,
                             @Param("market") NewsMarket market, @Param("limit") int limit);
}
