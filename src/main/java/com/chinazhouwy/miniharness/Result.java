package com.chinazhouwy.miniharness;

import com.chinazhouwy.miniharness.session.InterviewState;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 从本地 JSON 恢复后交给控制台的最小数据集合。
 *
 * <p>它仍只是当前 Demo 的局部载体，不是领域层的 Session；之所以增加当前题和状态，
 * 是因为它们决定了重启后的输入是否可以作为上一题的回答处理。</p>
 */
public record Result(
        List<QuestionAttempt> attempts,
        List<Message> history,
        InterviewState state,
        String currentQuestion
) {
}
