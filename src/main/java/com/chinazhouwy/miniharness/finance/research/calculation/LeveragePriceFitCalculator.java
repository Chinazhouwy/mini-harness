package com.chinazhouwy.miniharness.finance.research.calculation;

import com.chinazhouwy.miniharness.finance.research.domain.EquationFitPoint;
import com.chinazhouwy.miniharness.finance.research.domain.EquationFitResult;
import com.chinazhouwy.miniharness.finance.research.domain.MarketObservation;
import com.chinazhouwy.miniharness.finance.research.domain.MarketSeries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 探索“杠杆变化是否能解释价格变化”的小型回测器。
 *
 * <p>目标变量是下一交易日对数收益，而不是直接拟合价格水平：
 * <code>r(t+1) = β0 + β1*z(Lt) + β2*z(ΔLt) + ...</code>。
 * 模型只在每个市场自己的序列内标准化，并以时间顺序切分训练集和验证集，
 * 因此页面展示的是探索性拟合，不是预测或交易信号。</p>
 */
public final class LeveragePriceFitCalculator {

    private static final int SHORT_MOMENTUM_WINDOW = 5;
    private static final int LONG_MOMENTUM_WINDOW = 20;
    private static final double TRAIN_RATIO = 0.70;
    private static final double RIDGE = 1.0e-4;
    private static final double MAX_DAILY_LOG_RETURN = 0.25;

    public EquationFitResult fit(MarketSeries series) {
        List<MarketObservation> observations = series.observations().stream()
                .sorted(Comparator.comparing(MarketObservation::tradeDate))
                .toList();
        int firstIndex = LONG_MOMENTUM_WINDOW;
        int rowCount = observations.size() - firstIndex - 1;
        if (rowCount < 24) {
            throw new IllegalArgumentException(
                    "Not enough observations for leverage-price fitting: " + observations.size()
            );
        }

        List<FeatureRow> rows = buildRows(observations, firstIndex);
        int trainCount = Math.max(12, (int) Math.floor(rows.size() * TRAIN_RATIO));
        trainCount = Math.min(trainCount, rows.size() - 8);
        int finalTrainCount = trainCount;

        List<ModelSpec> candidates = List.of(
                new ModelSpec("LEVERAGE_LINEAR", List.of("z(L)", "z(ΔL)")),
                new ModelSpec("LEVERAGE_CURVE", List.of("z(L)", "z(ΔL)", "z(L)^2", "z(ΔL)^2")),
                new ModelSpec("LEVERAGE_MOMENTUM", List.of("z(L)", "z(ΔL)", "z(M5)", "z(M20)"))
        );

        List<FittedModel> fittedCandidates = candidates.stream()
                .map(spec -> fitModel(spec, rows, finalTrainCount))
                .toList();
        FittedModel best = fittedCandidates.stream()
                .min(Comparator.comparingDouble(FittedModel::validationRmsePct))
                .orElseThrow();
        List<EquationFitResult.CandidateFit> candidateFits = fittedCandidates.stream()
                .map(model -> new EquationFitResult.CandidateFit(
                        model.spec().name(),
                        model.equation(),
                        model.trainRmsePct(),
                        model.validationRmsePct(),
                        model.validationR2(),
                        model.validationCorrelation()
                ))
                .toList();

        List<EquationFitPoint> points = buildPoints(rows, best, finalTrainCount);
        return new EquationFitResult(
                best.spec().name(),
                best.equation(),
                rows.size(),
                finalTrainCount,
                best.trainRmsePct(),
                best.validationRmsePct(),
                best.validationR2(),
                best.validationCorrelation(),
                candidateFits,
                points,
                List.of(
                        "训练集按时间顺序占 " + formatPercent(TRAIN_RATIO) + "，验证集使用之后的交易日",
                        "回归系数只使用训练集求解，验证集不参与拟合",
                        "杠杆字段按市场保留原始语义，回归前只在本序列内做 z-score",
                        "预测日对数收益限制在 ±25%，用于避免异常值把价格路径画出数值范围"
                )
        );
    }

    private List<FeatureRow> buildRows(List<MarketObservation> observations, int firstIndex) {
        List<FeatureRow> rows = new ArrayList<>();
        for (int index = firstIndex; index < observations.size() - 1; index++) {
            MarketObservation previous = observations.get(index - 1);
            MarketObservation current = observations.get(index);
            MarketObservation shortAgo = observations.get(index - SHORT_MOMENTUM_WINDOW);
            MarketObservation longAgo = observations.get(index - LONG_MOMENTUM_WINDOW);
            MarketObservation next = observations.get(index + 1);

            double currentLevel = Math.log1p(current.leverageValue());
            double previousLevel = Math.log1p(previous.leverageValue());
            double deltaLevel = currentLevel - previousLevel;
            double momentum5 = Math.log(current.close() / shortAgo.close());
            double momentum20 = Math.log(current.close() / longAgo.close());
            double targetReturn = Math.log(next.close() / current.close());
            rows.add(new FeatureRow(
                    current.tradeDate(),
                    next.tradeDate(),
                    current.close(),
                    next.close(),
                    current.leverageValue(),
                    targetReturn,
                    new double[]{currentLevel, deltaLevel, momentum5, momentum20}
            ));
        }
        return List.copyOf(rows);
    }

    private FittedModel fitModel(ModelSpec spec, List<FeatureRow> rows, int trainCount) {
        Standardizer standardizer = Standardizer.from(rows, trainCount);
        double[][] design = new double[rows.size()][];
        double[] targets = new double[rows.size()];
        for (int index = 0; index < rows.size(); index++) {
            design[index] = standardizer.designRow(rows.get(index), spec);
            targets[index] = rows.get(index).targetReturn();
        }

        // 只用时间上更早的训练行求系数；验证行只能用于评估，不能参与回归。
        double[][] trainDesign = Arrays.copyOf(design, trainCount);
        double[] trainTargets = Arrays.copyOf(targets, trainCount);
        double[] coefficients = solveRidge(trainDesign, trainTargets, RIDGE);
        double[] predictions = new double[rows.size()];
        for (int index = 0; index < rows.size(); index++) {
            predictions[index] = bounded(design[index], coefficients);
        }
        return new FittedModel(
                spec,
                standardizer,
                coefficients,
                predictions,
                rmse(targets, predictions, 0, trainCount),
                rmse(targets, predictions, trainCount, rows.size()),
                r2(targets, predictions, trainCount, rows.size()),
                correlation(targets, predictions, trainCount, rows.size()),
                equation(spec, coefficients)
        );
    }

    private List<EquationFitPoint> buildPoints(List<FeatureRow> rows, FittedModel model, int trainCount) {
        List<EquationFitPoint> points = new ArrayList<>();
        double fittedClose = rows.getFirst().currentClose();
        for (int index = 0; index < rows.size(); index++) {
            fittedClose *= Math.exp(model.predictions()[index]);
            FeatureRow row = rows.get(index);
            points.add(new EquationFitPoint(
                    row.nextDate(),
                    row.nextClose(),
                    fittedClose,
                    row.targetReturn() * 100,
                    model.predictions()[index] * 100,
                    row.leverageValue(),
                    index >= trainCount
            ));
        }
        return List.copyOf(points);
    }

    private static double bounded(double[] designRow, double[] coefficients) {
        double prediction = dot(designRow, coefficients);
        return Math.max(-MAX_DAILY_LOG_RETURN, Math.min(MAX_DAILY_LOG_RETURN, prediction));
    }

    private static double[] solveRidge(double[][] design, double[] targets, double ridge) {
        int columns = design[0].length;
        double[][] normal = new double[columns][columns];
        double[] right = new double[columns];
        for (int row = 0; row < design.length; row++) {
            for (int left = 0; left < columns; left++) {
                right[left] += design[row][left] * targets[row];
                for (int rightColumn = 0; rightColumn < columns; rightColumn++) {
                    normal[left][rightColumn] += design[row][left] * design[row][rightColumn];
                }
            }
        }
        for (int diagonal = 1; diagonal < columns; diagonal++) {
            normal[diagonal][diagonal] += ridge;
        }
        return gaussianSolve(normal, right);
    }

    private static double[] gaussianSolve(double[][] matrix, double[] vector) {
        int size = vector.length;
        double[][] augmented = new double[size][size + 1];
        for (int row = 0; row < size; row++) {
            System.arraycopy(matrix[row], 0, augmented[row], 0, size);
            augmented[row][size] = vector[row];
        }
        for (int pivot = 0; pivot < size; pivot++) {
            int best = pivot;
            for (int row = pivot + 1; row < size; row++) {
                if (Math.abs(augmented[row][pivot]) > Math.abs(augmented[best][pivot])) {
                    best = row;
                }
            }
            if (Math.abs(augmented[best][pivot]) < 1.0e-12) {
                throw new IllegalArgumentException("Singular regression matrix");
            }
            double[] swap = augmented[pivot];
            augmented[pivot] = augmented[best];
            augmented[best] = swap;
            double divisor = augmented[pivot][pivot];
            for (int column = pivot; column <= size; column++) {
                augmented[pivot][column] /= divisor;
            }
            for (int row = 0; row < size; row++) {
                if (row == pivot) continue;
                double factor = augmented[row][pivot];
                for (int column = pivot; column <= size; column++) {
                    augmented[row][column] -= factor * augmented[pivot][column];
                }
            }
        }
        double[] result = new double[size];
        for (int row = 0; row < size; row++) {
            result[row] = augmented[row][size];
        }
        return result;
    }

    private static double rmse(double[] actual, double[] predicted, int start, int end) {
        if (start >= end) return 0;
        double sum = 0;
        for (int index = start; index < end; index++) {
            double difference = (actual[index] - predicted[index]) * 100;
            sum += difference * difference;
        }
        return Math.sqrt(sum / (end - start));
    }

    private static double r2(double[] actual, double[] predicted, int start, int end) {
        if (end - start < 2) return 0;
        double mean = Arrays.stream(actual, start, end).average().orElse(0);
        double total = 0;
        double residual = 0;
        for (int index = start; index < end; index++) {
            total += Math.pow(actual[index] - mean, 2);
            residual += Math.pow(actual[index] - predicted[index], 2);
        }
        return total == 0 ? 0 : 1 - residual / total;
    }

    private static double correlation(double[] actual, double[] predicted, int start, int end) {
        if (end - start < 2) return 0;
        double actualMean = Arrays.stream(actual, start, end).average().orElse(0);
        double predictedMean = Arrays.stream(predicted, start, end).average().orElse(0);
        double numerator = 0;
        double actualSum = 0;
        double predictedSum = 0;
        for (int index = start; index < end; index++) {
            double actualDelta = actual[index] - actualMean;
            double predictedDelta = predicted[index] - predictedMean;
            numerator += actualDelta * predictedDelta;
            actualSum += actualDelta * actualDelta;
            predictedSum += predictedDelta * predictedDelta;
        }
        double denominator = Math.sqrt(actualSum * predictedSum);
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private static double dot(double[] left, double[] right) {
        double result = 0;
        for (int index = 0; index < left.length; index++) {
            result += left[index] * right[index];
        }
        return result;
    }

    private static String equation(ModelSpec spec, double[] coefficients) {
        StringBuilder result = new StringBuilder("r(t+1) = ")
                .append(format(coefficients[0]));
        for (int index = 0; index < spec.features().size(); index++) {
            double coefficient = coefficients[index + 1];
            result.append(coefficient >= 0 ? " + " : " - ")
                    .append(format(Math.abs(coefficient)))
                    .append("·")
                    .append(spec.features().get(index));
        }
        return result.toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100);
    }

    private record FeatureRow(
            java.time.LocalDate currentDate,
            java.time.LocalDate nextDate,
            double currentClose,
            double nextClose,
            double leverageValue,
            double targetReturn,
            double[] rawFeatures
    ) {
    }

    private record ModelSpec(String name, List<String> features) {
    }

    private record FittedModel(
            ModelSpec spec,
            Standardizer standardizer,
            double[] coefficients,
            double[] predictions,
            double trainRmsePct,
            double validationRmsePct,
            double validationR2,
            double validationCorrelation,
            String equation
    ) {
    }

    private record Standardizer(double[] means, double[] deviations) {

        private static Standardizer from(List<FeatureRow> rows, int trainCount) {
            int dimensions = rows.getFirst().rawFeatures().length;
            double[] means = new double[dimensions];
            for (int row = 0; row < trainCount; row++) {
                for (int dimension = 0; dimension < dimensions; dimension++) {
                    means[dimension] += rows.get(row).rawFeatures()[dimension];
                }
            }
            for (int dimension = 0; dimension < dimensions; dimension++) {
                means[dimension] /= trainCount;
            }
            double[] deviations = new double[dimensions];
            for (int row = 0; row < trainCount; row++) {
                for (int dimension = 0; dimension < dimensions; dimension++) {
                    double delta = rows.get(row).rawFeatures()[dimension] - means[dimension];
                    deviations[dimension] += delta * delta;
                }
            }
            for (int dimension = 0; dimension < dimensions; dimension++) {
                deviations[dimension] = Math.sqrt(deviations[dimension] / Math.max(1, trainCount - 1));
                if (deviations[dimension] < 1.0e-12) deviations[dimension] = 1;
            }
            return new Standardizer(means, deviations);
        }

        private double designValue(FeatureRow row, int dimension) {
            return (row.rawFeatures()[dimension] - means[dimension]) / deviations[dimension];
        }

        private double[] designRow(FeatureRow row, ModelSpec spec) {
            double level = designValue(row, 0);
            double delta = designValue(row, 1);
            return switch (spec.name()) {
                case "LEVERAGE_LINEAR" -> new double[]{1, level, delta};
                case "LEVERAGE_CURVE" -> new double[]{1, level, delta, level * level, delta * delta};
                case "LEVERAGE_MOMENTUM" -> new double[]{1, level, delta, designValue(row, 2), designValue(row, 3)};
                default -> throw new IllegalArgumentException("Unsupported model: " + spec.name());
            };
        }
    }
}
