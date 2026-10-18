import { Decimal } from './formula-decimal';
import { callFunction } from './formula-functions';
import { bounded, compare, formulaText, formulaValue } from './formula-values';
import { FormulaFailure, type FormulaNode, type FormulaScope, type FormulaValue } from './formula.models';

/** Evaluated nodes of one evaluation (ADR-0042): a bound, as on the server. */
export const MAX_STEPS = 10_000;

/**
 * Evaluates a checked formula over a record with the server's semantics (ADR-0042): SQL null semantics — an arithmetic
 * operand or a compared value that is null makes the result null; three-valued `and`/`or`/`not` that stop early; `&`
 * reads null as an empty text; `if` and `coalesce` evaluate only what they return. `values` gives a field's raw value.
 *
 * @throws FormulaFailure when the formula cannot give a value for these values
 */
export function evaluateFormula(
  root: FormulaNode,
  values: (key: string) => unknown,
  scope: FormulaScope,
): FormulaValue {
  let steps = 0;
  const evaluate = (node: FormulaNode): FormulaValue => {
    if (++steps > MAX_STEPS) throw new FormulaFailure('budget');
    const args = node.args ?? [];
    switch (node.kind) {
      case 'literal':
        return literal(node);
      case 'field':
        return formulaValue(node.type, values(String(node.name)));
      case 'unary':
        return unary(String(node.name), evaluate(args[0]));
      case 'binary':
        return binary(String(node.name), args, evaluate);
      default:
        return call(String(node.name), args, evaluate, scope);
    }
  };
  return evaluate(root);
}

/** Whether a condition holds: only true holds — false and null do not. */
export function holds(root: FormulaNode, values: (key: string) => unknown, scope: FormulaScope): boolean {
  return evaluateFormula(root, values, scope) === true;
}

/** Whether a rule fails: only false fails — null passes, as a SQL check does. */
export function fails(root: FormulaNode, values: (key: string) => unknown, scope: FormulaScope): boolean {
  return evaluateFormula(root, values, scope) === false;
}

function literal(node: FormulaNode): FormulaValue {
  const value = node.value;
  if (value === null || value === undefined) return null;
  switch (node.type) {
    case 'number':
      return Decimal.parse(value);
    case 'bool':
      return value === 'true';
    default:
      return value;
  }
}

function unary(operator: string, operand: FormulaValue): FormulaValue {
  if (operand === null) return null;
  if (operator === 'not') return !(operand as boolean);
  return (operand as Decimal).negate();
}

function binary(operator: string, args: FormulaNode[], evaluate: (node: FormulaNode) => FormulaValue): FormulaValue {
  if (operator === 'and' || operator === 'or') {
    const and = operator === 'and';
    const left = evaluate(args[0]) as boolean | null;
    if (left !== null && left !== and) return left;
    const right = evaluate(args[1]) as boolean | null;
    if (right !== null && right !== and) return right;
    if (left === null || right === null) return null;
    return and;
  }
  const left = evaluate(args[0]);
  const right = evaluate(args[1]);
  if (operator === '&') return bounded(formulaText(left) + formulaText(right));
  if (left === null || right === null) return null;
  switch (operator) {
    case '+':
      return (left as Decimal).add(right as Decimal);
    case '-':
      return (left as Decimal).subtract(right as Decimal);
    case '*':
      return (left as Decimal).multiply(right as Decimal);
    case '/':
      return (left as Decimal).divide(right as Decimal);
    case '=':
      return compare(left, right) === 0;
    case '!=':
      return compare(left, right) !== 0;
    case '<':
      return compare(left, right) < 0;
    case '<=':
      return compare(left, right) <= 0;
    case '>':
      return compare(left, right) > 0;
    default:
      return compare(left, right) >= 0;
  }
}

function call(
  name: string,
  args: FormulaNode[],
  evaluate: (node: FormulaNode) => FormulaValue,
  scope: FormulaScope,
): FormulaValue {
  if (name === 'if') {
    const condition = evaluate(args[0]);
    return evaluate(condition === true ? args[1] : args[2]);
  }
  if (name === 'coalesce') {
    for (const arg of args) {
      const value = evaluate(arg);
      if (value !== null) return value;
    }
    return null;
  }
  return callFunction(name, args.map(evaluate), scope);
}
