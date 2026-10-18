import { FormulaFailure } from './formula.models';

/** Decimals a formula's number keeps (ADR-0042): more are rounded half away from zero. */
export const MAX_SCALE = 10;

/** Digits a formula's number may have before its decimal point. */
export const MAX_INTEGER_DIGITS = 24;

const NUMBER = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/;

/**
 * An exact decimal of the formula language (ADR-0042), the same arithmetic as the server's `BigDecimal`: an unscaled
 * BigInt and a scale. Every result keeps at most {@link MAX_SCALE} decimals (rounded half away from zero) and loses
 * its trailing zeros; one with more than {@link MAX_INTEGER_DIGITS} digits before the point is `number_too_large`.
 */
export class Decimal {
  private constructor(
    readonly unscaled: bigint,
    readonly scale: number,
  ) {}

  /** The number a text writes (`12`, `-0.5`, `1e3`), normalized; null for a text that is no number. */
  static parse(text: string): Decimal | null {
    const match = NUMBER.exec(text);
    if (!match) return null;
    const [, sign, whole, fraction = '', exponent] = match;
    if (whole === '' && fraction === '') return null;
    let unscaled = BigInt((whole || '0') + fraction);
    let scale = fraction.length - (exponent ? Number(exponent) : 0);
    if (!Number.isSafeInteger(scale) || Math.abs(scale) > 1000) return null;
    if (scale < 0) {
      unscaled *= 10n ** BigInt(-scale);
      scale = 0;
    }
    return Decimal.of(sign === '-' ? -unscaled : unscaled, scale);
  }

  /** A whole number. */
  static from(value: number | bigint): Decimal {
    return Decimal.of(BigInt(value), 0);
  }

  /** The value `unscaled × 10^-scale`, normalized. */
  static of(unscaled: bigint, scale: number): Decimal {
    let u = unscaled;
    let s = scale;
    if (s > MAX_SCALE) {
      u = roundHalfUp(u, 10n ** BigInt(s - MAX_SCALE));
      s = MAX_SCALE;
    }
    if (u === 0n) return new Decimal(0n, 0);
    while (s > 0 && u % 10n === 0n) {
      u /= 10n;
      s--;
    }
    if (digits(u) - s > MAX_INTEGER_DIGITS) throw new FormulaFailure('number_too_large');
    return new Decimal(u, s);
  }

  add(other: Decimal): Decimal {
    const [a, b, s] = align(this, other);
    return Decimal.of(a + b, s);
  }

  subtract(other: Decimal): Decimal {
    const [a, b, s] = align(this, other);
    return Decimal.of(a - b, s);
  }

  multiply(other: Decimal): Decimal {
    return Decimal.of(this.unscaled * other.unscaled, this.scale + other.scale);
  }

  /** The quotient rounded half away from zero to {@link MAX_SCALE} decimals; by zero is `division_by_zero`. */
  divide(other: Decimal): Decimal {
    if (other.unscaled === 0n) throw new FormulaFailure('division_by_zero');
    const numerator = this.unscaled * 10n ** BigInt(other.scale + MAX_SCALE);
    const denominator = other.unscaled * 10n ** BigInt(this.scale);
    const negative = numerator < 0n !== denominator < 0n;
    const quotient = roundHalfUp(abs(numerator), abs(denominator));
    return Decimal.of(negative ? -quotient : quotient, MAX_SCALE);
  }

  /** The number rounded half away from zero to `places` decimals. */
  round(places: number): Decimal {
    if (this.scale <= places) return this;
    return Decimal.of(roundHalfUp(this.unscaled, 10n ** BigInt(this.scale - places)), places);
  }

  negate(): Decimal {
    return Decimal.of(-this.unscaled, this.scale);
  }

  abs(): Decimal {
    return this.unscaled < 0n ? this.negate() : this;
  }

  compare(other: Decimal): number {
    const [a, b] = align(this, other);
    return a < b ? -1 : a > b ? 1 : 0;
  }

  /** The value as a whole number of at most a million, or `invalid_argument`. */
  whole(): number {
    if (this.scale > 0 || abs(this.unscaled) > 1_000_000n) throw new FormulaFailure('invalid_argument');
    return Number(this.unscaled);
  }

  /** The value with exactly `places` decimals (a field's scale), rounded half away from zero; digits padded. */
  fixed(places: number): string {
    const rounded = this.round(places);
    const padded = rounded.unscaled * 10n ** BigInt(places - rounded.scale);
    return plain(padded, places);
  }

  /** The canonical text: plain digits, a point only before decimals (`-12.5`, `3`). */
  toString(): string {
    return plain(this.unscaled, this.scale);
  }
}

function plain(unscaled: bigint, scale: number): string {
  const digitsText = abs(unscaled)
    .toString()
    .padStart(scale + 1, '0');
  const sign = unscaled < 0n ? '-' : '';
  if (scale === 0) return sign + digitsText;
  return `${sign}${digitsText.slice(0, -scale)}.${digitsText.slice(-scale)}`;
}

function align(a: Decimal, b: Decimal): [bigint, bigint, number] {
  const scale = Math.max(a.scale, b.scale);
  return [a.unscaled * 10n ** BigInt(scale - a.scale), b.unscaled * 10n ** BigInt(scale - b.scale), scale];
}

/** `value / divisor` rounded half away from zero; `divisor` is positive. */
function roundHalfUp(value: bigint, divisor: bigint): bigint {
  const negative = value < 0n;
  const magnitude = abs(value);
  let quotient = magnitude / divisor;
  if ((magnitude % divisor) * 2n >= divisor) quotient += 1n;
  return negative ? -quotient : quotient;
}

function abs(value: bigint): bigint {
  return value < 0n ? -value : value;
}

function digits(value: bigint): number {
  return value === 0n ? 1 : abs(value).toString().length;
}
