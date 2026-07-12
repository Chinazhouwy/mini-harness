package com.chinazhouwy.miniharness;

/**
 * 意图识别模型的结构化输出。
 *
 * <p>控制台收到一行文本后，并不直接假定它一定是答案：用户也可能说“下一题”、
 * “给个提示”或“解释一下”。这个 Record 把模型的判断收窄成一个枚举，随后由 Java
 * 的 {@code switch} 决定走哪条流程。</p>
 */
public record IntentResult(InterviewIntent intent) {
}
