package com.chinazhouwy.miniharness.runtime.evolution;

import java.util.ArrayList;
import java.util.List;

/** 评测案例集：训练集用于提出改进，留出集用于独立验证，两集不得重叠。 */
public record EvalCaseSet(List<EvalCase> trainCases, List<EvalCase> holdoutCases) {

    public EvalCaseSet {
        trainCases = List.copyOf(trainCases);
        holdoutCases = List.copyOf(holdoutCases);
    }

    public List<EvalCase> all() {
        return concat(trainCases, holdoutCases);
    }

    private static List<EvalCase> concat(List<EvalCase> a, List<EvalCase> b) {
        List<EvalCase> all = new ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
