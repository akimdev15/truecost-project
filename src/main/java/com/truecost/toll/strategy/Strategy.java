package com.truecost.toll.strategy;

/**
 * The four strategies the toll strategy engine ranks for one rental option and one route. A
 * company that offers more than one program contributes more than one company strategy
 * candidate, and the winner names both the strategy kind and, for company strategies, which
 * program won.
 */
public enum Strategy {
    PERSONAL_TAG,
    COMPANY_PER_CROSSING,
    COMPANY_UNLIMITED,
    NO_ARRANGEMENT
}
