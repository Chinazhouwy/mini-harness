package com.chinazhouwy.miniharness.runtime.capability;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则意图抽取器：纯确定性正则，不调用任何模型。
 * 第一阶段覆盖金融研究 Domain Pack 的两类意图；新增意图按同样方式扩展。
 */
public class RuleIntentExtractor {

    public static final String INTENT_PROFITABILITY_RESEARCH = "PROFITABILITY_RESEARCH";
    public static final String INTENT_CHALLENGE_RESULT = "CHALLENGE_RESULT";

    private static final Pattern RESEARCH_PATTERN =
            Pattern.compile("(?:研究|分析)(?:一下)?(.+?)(?:公司)?的(?:盈利能力|利润率|盈利情况|毛利率|净利率)");
    private static final Pattern CHALLENGE_PATTERN =
            Pattern.compile("(利润率|净利率|毛利率|数据|结果|结论|报告|口径).{0,8}(不对|错了|有问题|可疑|存疑|不靠谱|奇怪|对吗)");
    private static final Pattern COMPANY_SUFFIX = Pattern.compile("^(.+?)(?:公司|集团|股份)$");

    /** 抽取结果：意图 + 实体（公司名，质疑类消息可能没有实体）。 */
    public record ExtractedIntent(String intent, String company, String matchedBy) {
    }

    public Optional<ExtractedIntent> extract(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Optional.empty();
        }
        String text = userInput.trim();

        Matcher challenge = CHALLENGE_PATTERN.matcher(text);
        if (challenge.find()) {
            String company = extractCompanyFromResearch(text);
            return Optional.of(new ExtractedIntent(INTENT_CHALLENGE_RESULT, company, "challenge-pattern"));
        }
        Matcher research = RESEARCH_PATTERN.matcher(text);
        if (research.find()) {
            String company = cleanCompany(research.group(1));
            if (!company.isBlank()) {
                return Optional.of(new ExtractedIntent(INTENT_PROFITABILITY_RESEARCH, company, "research-pattern"));
            }
        }
        return Optional.empty();
    }

    /** 「这个利润率不对」式消息若同时提到公司名（如"XX公司的利润率不对"），也一并抽取。 */
    private String extractCompanyFromResearch(String text) {
        Matcher research = RESEARCH_PATTERN.matcher(text);
        if (research.find()) {
            return cleanCompany(research.group(1));
        }
        return null;
    }

    private String cleanCompany(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("^[\"'“”\\s]+|[\"'“”\\s]+$", "").trim();
        Matcher suffix = COMPANY_SUFFIX.matcher(cleaned);
        return suffix.matches() ? suffix.group(1) : cleaned;
    }
}
