package com.pauleternity.marketdaily.mapper;

import com.pauleternity.marketdaily.model.report.ReportScheduleStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.time.LocalDate;

/** 读写自动日报的采集与生成任务执行记录。 */
@Mapper
public interface TaskExecutionMapper {

    /** 按业务日和尝试次数取该类任务的最新记录，复用已有联合索引。 */
    @Select("""
            SELECT business_date, attempt_no, status, started_at, finished_at, error_message
            FROM task_execution WHERE task_type = #{type}
            ORDER BY business_date DESC, attempt_no DESC LIMIT 1
            """)
    ReportScheduleStatus.Task latestExecution(@Param("type") String type);

    @Select("""
            SELECT COALESCE(MAX(attempt_no), 0) FROM task_execution
            WHERE task_type = #{type} AND business_date = #{date}
            """)
    int lastAttempt(@Param("type") String type, @Param("date") LocalDate date);

    @Select("""
            SELECT status FROM task_execution WHERE task_type = #{type} AND business_date = #{date}
            ORDER BY attempt_no DESC LIMIT 1
            """)
    String latestStatus(@Param("type") String type, @Param("date") LocalDate date);

    @Insert("""
            INSERT INTO task_execution (task_type, business_date, attempt_no, trace_id, status, started_at)
            VALUES (#{type}, #{date}, #{attempt}, #{traceId}, 'RUNNING', #{now})
            """)
    void start(@Param("type") String type, @Param("date") LocalDate date, @Param("attempt") int attempt,
               @Param("traceId") String traceId, @Param("now") Instant now);

    @Update("""
            UPDATE task_execution SET status = #{status}, error_message = #{error}, finished_at = #{now}
            WHERE trace_id = #{traceId} AND status = 'RUNNING'
            """)
    void finish(@Param("traceId") String traceId, @Param("status") String status,
                @Param("error") String error, @Param("now") Instant now);

    @Update("""
            UPDATE task_execution SET status = 'INTERRUPTED', error_message = '进程在任务完成前退出', finished_at = #{now}
            WHERE task_type IN ('DAILY_INPUT', 'DAILY_REPORT') AND status = 'RUNNING'
            """)
    void markInterrupted(@Param("now") Instant now);
}
