package com.chinazhouwy.miniharness.session;

public enum QuestionStatus {

    /** 题目还没有被出。 */
    NOT_ANSWERED,

    /** 题目已经出，但还没有收到候选人回答。 */
    ASKED,

    /** 已经收到候选人回答，但还没有给出评分或追问。 */
    ANSWERED,

    /** 已经给出评分或追问，允许围绕当前题继续讨论。 */
    DISCUSSING,

    /** 面试已经显式结束。当前控制台退出时还没有设置此值。 */
    FINISHED

}
