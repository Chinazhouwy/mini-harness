package com.chinazhouwy.miniharness;

import java.util.List;

/**
 * 一道面试题的聚合草稿。
 *
 * <p>当前控制台 Demo 仍把 {@code currentQuestion} 保存为一个字符串；这个类是下一步
 * 准备把“主问题 + 多次追问 + 每轮评测”收拢到一个对象时留下的领域草图。它还没有被
 * 主流程使用，也没有构造方法和状态转换方法，所以不要把它误认为已经完成的领域模型。</p>
 */
public class InterviewQuestion {

    /** 本题最开始向候选人提出的主问题。 */
    private String mainQuestion;

    /** 主问题及后续追问形成的轮次列表。 */
    private List<QuestionRound> rounds;

    /**
     * 本题自己的进度。这里引用的 {@code QuestionStatus} 还没有在仓库中定义，
     * 因此当前代码不能编译；这是正在整理的草稿，而非一个可用实现。
     */
//    private QuestionStatus status;

}
