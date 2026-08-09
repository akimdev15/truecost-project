package com.truecost.toll.strategy;

/**
 * Selects which of a crossing's two prices a company toll program bills at. EZPASS bills the
 * discounted transponder rate, MAX_CASH bills the higher Tolls by Mail or maximum cash rate,
 * which is how several programs mark up tolls.
 */
public enum TollRateBasis {
    EZPASS,
    MAX_CASH
}
