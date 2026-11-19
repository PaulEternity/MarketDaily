package com.pauleternity.marketdaily.report;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 复用已有 daily_report 表存取日报与生成元数据。 */
@Mapper
public interface ReportMapper {

    /** 读取指定业务日的成功结果，JSON 列按文本读取后由 Jackson 解析。 */
    @Select("""
            SELECT report_date, overall_summary, CAST(report_content AS CHAR CHARACTER SET utf8mb4),
                   input_hash, model_name, prompt_version, input_tokens, output_tokens, generated_at
            FROM daily_report WHERE report_date = #{date} AND status = 'SUCCESS'
            """)
    Stored onDate(@Param("date") LocalDate date);

    /** 列表仅取摘要，完整证据由详情接口读取。 */
    @Select("""
            SELECT report_date, overall_summary, model_name, generated_at
            FROM daily_report WHERE status = 'SUCCESS'
            ORDER BY report_date DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<Summary> page(@Param("size") int size, @Param("offset") long offset);

    /** 统计已保存的日报数量。 */
    @Select("SELECT COUNT(*) FROM daily_report WHERE status = 'SUCCESS'")
    long count();

    /** 校验通过后才写入，唯一键保护同一日期不会存下两份结果。 */
    @Insert("""
            INSERT INTO daily_report
              (report_date, overall_summary, report_content, input_hash, model_name, prompt_version,
               input_tokens, output_tokens, status, generated_at)
            VALUES (#{date}, #{summary}, #{content}, #{inputHash}, #{model}, #{promptVersion},
                    #{inputTokens}, #{outputTokens}, 'SUCCESS', #{generatedAt})
            """)
    void insert(Stored report);

    /** 数据库中的日报详情，字段顺序与 SELECT 一致。 */
    record Stored(LocalDate date, String summary, String content, String inputHash, String model,
                  String promptVersion, Integer inputTokens, Integer outputTokens, Instant generatedAt) {
    }

    /** 历史列表摘要。 */
    record Summary(LocalDate date, String summary, String model, Instant generatedAt) {
    }
}
