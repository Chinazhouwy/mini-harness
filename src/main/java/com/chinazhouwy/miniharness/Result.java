package com.chinazhouwy.miniharness;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/** 将加载后的两个列表打包返回。它只是当前 Demo 的局部载体，不是领域层的 Session。 */
public record Result(List<QuestionAttempt> attempts, List<Message> history) {
}
