package com.chinazhouwy.miniharness;

/**
 * 用户在控制台里输入一句话后，系统希望识别出的“操作意图”。
 *
 * <p>这里故意把“理解用户在说什么”交给模型，把“接下来允许做什么”留给 Java。
 * 模型只负责返回枚举值，不能自己直接结束面试或修改历史记录。</p>
 */
public enum InterviewIntent {


    QUESTION, // 用户请求问题

    HINT, // 用户请求提示

    EXPLAIN, // 用户请求讲解

    ANSWER,  //用户回答

    FOLLOW_UP,  // 用户针对评分、答案或讲解继续提问

    CHAT,  //用户聊天

    EXIT //用户退出

}
