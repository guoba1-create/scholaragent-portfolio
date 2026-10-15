package com.team.rag.entity;

/**
 * CRAG 相关性判断结果实体
 *
 * <p>
 * 用于存储 LLM 对检索内容相关性的判断结果。
 *
 * <h3>三种判断结果：</h3>
 * <ul>
 * <li>SUFFICIENT: 检索内容中包含足够的信息来回答问题</li>
 * <li>PARTIAL: 检索内容中包含部分相关信息，但不足以完整回答</li>
 * <li>IRRELEVANT: 检索内容与用户问题基本无关</li>
 * </ul>
 */
public class CragResult {

    /** 判定结果：检索内容足以完整回答用户问题 */
    public static final String SUFFICIENT = "SUFFICIENT";

    /** 判定结果：检索内容包含部分相关信息，但不足以完整回答 */
    public static final String PARTIAL = "PARTIAL";

    /** 判定结果：检索内容与用户问题基本无关 */
    public static final String IRRELEVANT = "IRRELEVANT";

    /** 当前判定结果，取值为上述三个常量之一 */
    private String judgment;

    /** 判定原因说明，解释 LLM 为何给出该判定 */
    private String reason;

    public CragResult() {
    }

    public CragResult(String judgment, String reason) {
        this.judgment = judgment;
        this.reason = reason;
    }

    public String getJudgment() {
        return judgment;
    }

    public void setJudgment(String judgment) {
        this.judgment = judgment;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    /** 判断当前结果是否为"内容充足"（SUFFICIENT） */
    public boolean isSufficient() {
        return SUFFICIENT.equals(judgment);
    }

    /** 判断当前结果是否为"部分相关"（PARTIAL） */
    public boolean isPartial() {
        return PARTIAL.equals(judgment);
    }

    /** 判断当前结果是否为"内容无关"（IRRELEVANT） */
    public boolean isIrrelevant() {
        return IRRELEVANT.equals(judgment);
    }

    @Override
    public String toString() {
        return "CragResult{" +
                "judgment='" + judgment + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}
