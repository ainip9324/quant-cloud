package com.quant.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.core.entity.BacktestTaskDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回测任务 Mapper（MyBatis-Plus BaseMapper + 自定义条件更新）
 *
 * <p>Week 3 关键方法均使用条件更新（WHERE status=? AND execution_token=?），
 * 保证原子领取、防止旧执行者覆盖新执行者结果。
 */
public interface BacktestTaskMapper extends BaseMapper<BacktestTaskDO> {

    /**
     * 普通详情查询：按 id + userId 做用户隔离，不加载 replay_data 或 execution_token。
     */
    @Select("SELECT id, user_id, strategy_id, market_type, symbol, `interval`, start_ts, end_ts, " +
            "params, status, result_summary, error_msg, bar_limit, create_time, update_time " +
            "FROM backtest_task WHERE id=#{id} AND user_id=#{userId}")
    BacktestTaskDO selectTaskByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    // ==================== 原子领取与终态更新 ====================

    /**
     * 原子领取任务：WAITING → RUNNING，同时写入 executionToken
     *
     * <p>利用 MySQL 行锁保证只有一个消费者能成功领取。
     * affected=0 表示任务已被其他消费者领取或状态已变。
     *
     * @param id    任务 ID
     * @param token 本次执行的唯一令牌（UUID）
     * @return 受影响行数（1=领取成功，0=已被领取）
     */
    @Update("UPDATE backtest_task SET status='RUNNING', execution_token=#{token}, " +
            "update_time=NOW() WHERE id=#{id} AND status='WAITING'")
    int claimTask(@Param("id") Long id, @Param("token") String token);

    /**
     * 条件更新为 SUCCESS（带 token 校验，防旧执行者覆盖）
     *
     * @return 1=写入成功，0=token不匹配（已被Watchdog恢复并被新执行者接管）
     */
    @Update("UPDATE backtest_task SET status='SUCCESS', result_summary=#{resultJson}, " +
            "update_time=NOW() WHERE id=#{id} AND status='RUNNING' AND execution_token=#{token}")
    int updateSuccess(@Param("id") Long id, @Param("token") String token,
                      @Param("resultJson") String resultJson);

    /**
     * 条件更新为 SUCCESS，同时写入 result_summary 与 replay_data（Week 7）
     *
     * <p>两者必须在<b>同一条</b>带 status='RUNNING' AND execution_token 条件的更新中写入，
     * 保证旧执行者不能覆盖新执行者的结果摘要与回放快照（affected=0 时两者都不写入）。
     *
     * @return 1=写入成功，0=token不匹配（已被Watchdog恢复并被新执行者接管，不得覆盖）
     */
    @Update("UPDATE backtest_task SET status='SUCCESS', result_summary=#{resultJson}, " +
            "replay_data=#{replayJson}, update_time=NOW() " +
            "WHERE id=#{id} AND status='RUNNING' AND execution_token=#{token}")
    int updateSuccessWithReplay(@Param("id") Long id, @Param("token") String token,
                                @Param("resultJson") String resultJson,
                                @Param("replayJson") String replayJson);

    /**
     * 条件更新为 FAILED（带 token 校验）
     */
    @Update("UPDATE backtest_task SET status='FAILED', error_msg=#{errorMsg}, " +
            "update_time=NOW() WHERE id=#{id} AND status='RUNNING' AND execution_token=#{token}")
    int updateFailed(@Param("id") Long id, @Param("token") String token,
                     @Param("errorMsg") String errorMsg);

    // ==================== Week 7 回放专用查询 ====================

    /**
     * 回放专用查询：按 id + userId 加载任务（含 replay_data）
     *
     * <p>用户隔离直接落实在 SQL 条件中：id 存在但不属于该用户时返回 null，
     * 与"任务不存在"表现一致，不泄漏任务存在性。
     * 只有回放接口使用此查询，普通 getTask/listTasks 不加载 replay_data。
     *
     * @return 任务记录（含 replayData），不存在或不属于该用户返回 null
     */
    @Select("SELECT id, user_id, strategy_id, market_type, symbol, `interval`, start_ts, end_ts, " +
            "params, status, result_summary, error_msg, replay_data, create_time, update_time " +
            "FROM backtest_task WHERE id=#{id} AND user_id=#{userId}")
    BacktestTaskDO selectReplayByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 条件恢复为 WAITING（基础设施异常时，让 MQ 重试）
     *
     * @return 1=恢复成功，0=token不匹配（已被Watchdog恢复或新执行者接管）
     */
    @Update("UPDATE backtest_task SET status='WAITING', update_time=NOW() " +
            "WHERE id=#{id} AND status='RUNNING' AND execution_token=#{token}")
    int revertToWaiting(@Param("id") Long id, @Param("token") String token);

    // ==================== DLQ 终态处理 ====================

    /**
     * DLQ 消费者调用：将仍为 WAITING 的任务标记为 FAILED（MQ_RETRY_EXHAUSTED）
     *
     * <p>只处理 WAITING 状态——基础设施异常时 Processor 已 revert 为 WAITING，
     * MQ 重试 3 次后进 DLQ，此时任务应在 WAITING 状态。
     * 已是 SUCCESS/FAILED 的不受影响（幂等）。
     */
    @Update("UPDATE backtest_task SET status='FAILED', error_msg=#{errorMsg}, " +
            "update_time=NOW() WHERE id=#{id} AND status='WAITING'")
    int markFailedIfWaiting(@Param("id") Long id, @Param("errorMsg") String errorMsg);

    // ==================== Watchdog 超时查询 ====================

    /**
     * 查询 RUNNING 超时任务（进程崩溃恢复）
     *
     * @param beforeTime 超时阈值（update_time < beforeTime 的任务视为超时）
     * @param limit      每轮限量
     */
    @Select("SELECT id FROM backtest_task WHERE status='RUNNING' " +
            "AND update_time < #{beforeTime} LIMIT #{limit}")
    List<Long> findTimeoutRunning(@Param("beforeTime") LocalDateTime beforeTime,
                                  @Param("limit") int limit);

    /**
     * 查询 WAITING 超时任务（消息丢失补偿投递）
     *
     * <p>使用 update_time 而非 create_time：任务入库后 update_time=createTime，
     * MQ 消费者领取后 update_time 更新为 RUNNING 时间。
     * 用 update_time 可以确保：已被 touch/claim 的任务不会重复投递。
     */
    @Select("SELECT id FROM backtest_task WHERE status='WAITING' " +
            "AND update_time < #{beforeTime} LIMIT #{limit}")
    List<Long> findTimeoutWaiting(@Param("beforeTime") LocalDateTime beforeTime,
                                  @Param("limit") int limit);

    /**
     * 原子 touch WAITING 任务：更新 update_time，返回是否成功
     *
     * <p>Watchdog 补偿投递前调用：只有 touch 成功（affected=1）才重新投递消息。
     * touch 成功后 update_time 刷新为当前时间，避免同一任务每分钟重复投递。
     * 如果任务已被 MQ 消费者领取（status≠WAITING），touch 返回 0，不投递。
     *
     * <p>带 {@code update_time < beforeTime} 时间条件：多实例 Watchdog 并发时，
     * 只有一个实例能 touch 成功（第一个更新后 update_time 刷新，后续实例时间条件不满足），
     * 确保同一任务不会被多实例重复投递。
     *
     * @param beforeTime 超时阈值（必须与 findTimeoutWaiting 使用相同阈值）
     * @return 1=touch成功(可投递)，0=任务已被领取/状态已变/已被其他实例touch(跳过)
     */
    @Update("UPDATE backtest_task SET update_time=NOW() " +
            "WHERE id=#{id} AND status='WAITING' AND update_time < #{beforeTime}")
    int touchWaiting(@Param("id") Long id, @Param("beforeTime") LocalDateTime beforeTime);

    /**
     * Watchdog 恢复超时 RUNNING → WAITING（带时间条件，防误回收正在执行的任务）
     */
    @Update("UPDATE backtest_task SET status='WAITING', update_time=NOW() " +
            "WHERE id=#{id} AND status='RUNNING' AND update_time < #{beforeTime}")
    int revertTimedOutRunning(@Param("id") Long id,
                              @Param("beforeTime") LocalDateTime beforeTime);
}
