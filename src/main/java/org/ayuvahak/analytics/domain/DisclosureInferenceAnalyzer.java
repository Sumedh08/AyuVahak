package org.ayuvahak.analytics.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Exact, bounded row-space analysis for configured additive disclosure relationships. */
public final class DisclosureInferenceAnalyzer {

  private static final int MAX_EQUATIONS = 256;
  private static final int MAX_VARIABLES = 512;

  public Result evaluate(
      DisclosurePolicyVersion policy,
      DisclosureCandidate candidate,
      Collection<DisclosureOutput> priorOutputs,
      Map<String, Long> priorCellValues,
      boolean historyComplete) {
    if (!historyComplete || priorOutputs.size() + candidate.outputs().size() > MAX_EQUATIONS) {
      return Result.REVIEW_REQUIRED;
    }

    Map<String, Long> cells = new HashMap<>(priorCellValues);
    for (Map.Entry<String, Long> cell : candidate.cellValues().entrySet()) {
      Long prior = cells.putIfAbsent(cell.getKey(), cell.getValue());
      if (prior != null && !prior.equals(cell.getValue())) {
        return Result.REVIEW_REQUIRED;
      }
    }
    List<DisclosureOutput> equations = new ArrayList<>(priorOutputs);
    equations.addAll(candidate.outputs());
    Set<String> variables = new TreeSet<>();
    equations.forEach(output -> variables.addAll(output.cellCoefficients().keySet()));
    if (variables.isEmpty() || variables.size() > MAX_VARIABLES) {
      return Result.REVIEW_REQUIRED;
    }

    List<String> orderedVariables = variables.stream().sorted(Comparator.naturalOrder()).toList();
    List<String> protectedCells =
        cells.entrySet().stream()
            .filter(entry -> entry.getValue() < policy.minimumCellCount())
            .map(Map.Entry::getKey)
            .filter(variables::contains)
            .sorted()
            .toList();
    if (!policy.externalBaselineComplete() && !protectedCells.isEmpty()) {
      return Result.REVIEW_REQUIRED;
    }
    if (protectedCells.isEmpty()) {
      return Result.SAFE;
    }

    Fraction[][] matrix = matrix(equations, orderedVariables);
    int baseRank = rank(matrix);
    for (String protectedCell : protectedCells) {
      Fraction[][] withCellEquation =
          appendUnitRow(matrix, orderedVariables.indexOf(protectedCell));
      if (rank(withCellEquation) == baseRank) {
        return Result.INFERENCE_RISK;
      }
    }
    return Result.SAFE;
  }

  private static Fraction[][] matrix(List<DisclosureOutput> outputs, List<String> variables) {
    Fraction[][] rows = new Fraction[outputs.size()][variables.size()];
    for (int row = 0; row < outputs.size(); row++) {
      Map<String, Long> coefficients = outputs.get(row).cellCoefficients();
      for (int column = 0; column < variables.size(); column++) {
        rows[row][column] = Fraction.of(coefficients.getOrDefault(variables.get(column), 0L));
      }
    }
    return rows;
  }

  private static Fraction[][] appendUnitRow(Fraction[][] matrix, int column) {
    Fraction[][] expanded = new Fraction[matrix.length + 1][matrix[0].length];
    for (int row = 0; row < matrix.length; row++) {
      expanded[row] = matrix[row].clone();
    }
    for (int index = 0; index < matrix[0].length; index++) {
      expanded[matrix.length][index] = index == column ? Fraction.ONE : Fraction.ZERO;
    }
    return expanded;
  }

  private static int rank(Fraction[][] source) {
    if (source.length == 0) {
      return 0;
    }
    Fraction[][] values = new Fraction[source.length][source[0].length];
    for (int row = 0; row < source.length; row++) {
      values[row] = source[row].clone();
    }
    int pivotRow = 0;
    for (int column = 0; column < values[0].length && pivotRow < values.length; column++) {
      int selected = pivotRow;
      while (selected < values.length && values[selected][column].isZero()) {
        selected++;
      }
      if (selected == values.length) {
        continue;
      }
      Fraction[] swap = values[pivotRow];
      values[pivotRow] = values[selected];
      values[selected] = swap;
      Fraction pivot = values[pivotRow][column];
      for (int index = column; index < values[pivotRow].length; index++) {
        values[pivotRow][index] = values[pivotRow][index].divide(pivot);
      }
      for (int row = 0; row < values.length; row++) {
        if (row == pivotRow || values[row][column].isZero()) {
          continue;
        }
        Fraction factor = values[row][column];
        for (int index = column; index < values[row].length; index++) {
          values[row][index] =
              values[row][index].subtract(factor.multiply(values[pivotRow][index]));
        }
      }
      pivotRow++;
    }
    return pivotRow;
  }

  public enum Result {
    SAFE,
    INFERENCE_RISK,
    REVIEW_REQUIRED
  }

  private record Fraction(BigInteger numerator, BigInteger denominator) {
    private static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);
    private static final Fraction ONE = new Fraction(BigInteger.ONE, BigInteger.ONE);

    private Fraction {
      if (denominator.signum() == 0) {
        throw new ArithmeticException("fraction denominator cannot be zero");
      }
      if (denominator.signum() < 0) {
        numerator = numerator.negate();
        denominator = denominator.negate();
      }
      BigInteger gcd = numerator.gcd(denominator);
      numerator = numerator.divide(gcd);
      denominator = denominator.divide(gcd);
    }

    private static Fraction of(long value) {
      return value == 0 ? ZERO : new Fraction(BigInteger.valueOf(value), BigInteger.ONE);
    }

    private boolean isZero() {
      return numerator.signum() == 0;
    }

    private Fraction subtract(Fraction other) {
      return new Fraction(
          numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
          denominator.multiply(other.denominator));
    }

    private Fraction multiply(Fraction other) {
      return new Fraction(
          numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    private Fraction divide(Fraction other) {
      return new Fraction(
          numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }
  }
}
