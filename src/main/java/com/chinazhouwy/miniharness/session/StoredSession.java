package com.chinazhouwy.miniharness.session;

import com.chinazhouwy.miniharness.QuestionAttempt;

/**
 * 写入 {@code data/session.json} 的最小会话快照。
 *
 * <p>{@link StoredMessage} 保存“对话说了什么”，{@link QuestionAttempt} 保存“某道题怎样被评测”。
 * 这两个文件都不能可靠告诉程序：重启前是否正在等待一道题的回答。因此单独保存当前阶段和当前题目，
 * 让控制台重启后仍能判断下一句输入是不是对上一题的回答。</p>
 *
 * <p>它不是最终的领域 {@code InterviewSession}，只是为当前已经复现的重启问题补上的最小 DTO。</p>
 */
public record StoredSession(
        InterviewState state,
        String currentQuestion
) {

    /** 没有 session.json 时使用的安全初始状态。 */
    public static StoredSession empty() {
        return new StoredSession(InterviewState.IDLE, "");
    }
}
