package com.chinazhouwy.miniharness;

/**
 * 用来区分题目在一条训练链中的位置。
 */
public enum QuestionType {

    /** 系统主动选择的训练题。 */
    MAIN,

    /** 根据上一轮回答的缺口提出的追问。 */
    FOLLOW_UP

}
