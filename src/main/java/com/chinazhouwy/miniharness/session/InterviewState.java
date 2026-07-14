package com.chinazhouwy.miniharness.session;

/**
 * 控制台原型记录的面试阶段。
 *
 * <p>它目前主要用于打印运行状态，尚未被封装为严格的状态机：例如代码还没有阻止用户
 * 在 {@link #IDLE} 时直接输入一段答案。保留这个枚举的目的，是先把“模型行为”和
 * “业务流程状态”这两个概念分开，后续再决定哪些迁移需要由 Java 强制校验。</p>
 */
public enum InterviewState {

    /** 尚未出题，或刚启动控制台。 */
    IDLE,

    /** 已经出题，正在等待候选人作答。 */
    WAITING_ANSWER,

    /** 已给出评分或追问，允许围绕当前题继续讨论。 */
    DISCUSSING,

    /** 面试已经显式结束。当前控制台退出时还没有设置此值。 */
    FINISHED

}
