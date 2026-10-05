package io.jettra.store.calc;

import java.util.ArrayList;
import java.util.List;

/**
 * Motor Financiero Cuantitativo para JettraStore.
 */
public final class JettraFinance {

    public record AmortizationRow(
        int period,
        double payment,
        double principalPart,
        double interestPart,
        double remainingBalance
    ) {}

    private JettraFinance() {}

    /**
     * Calcula la cuota periódica fija de un préstamo (PMT).
     * @param rate Tasa de interés por período (ej. 0.05 / 12 para mensual)
     * @param nper Número total de períodos de pago
     * @param pv Valor presente (monto del préstamo)
     */
    public static double pmt(double rate, int nper, double pv) {
        if (nper <= 0) throw new IllegalArgumentException("nper must be positive: " + nper);
        if (rate == 0.0) return pv / nper;
        double factor = Math.pow(1.0 + rate, nper);
        return (pv * rate * factor) / (factor - 1.0);
    }

    /**
     * Calcula el valor futuro de una inversión (FV).
     */
    public static double fv(double rate, int nper, double pmt, double pv) {
        if (rate == 0.0) return -(pv + pmt * nper);
        double factor = Math.pow(1.0 + rate, nper);
        return -(pv * factor + (pmt / rate) * (factor - 1.0));
    }

    /**
     * Calcula el valor presente de una inversión o flujo futuro (PV).
     */
    public static double pv(double rate, int nper, double pmt, double fv) {
        if (rate == 0.0) return -(fv + pmt * nper);
        double factor = Math.pow(1.0 + rate, nper);
        return -(fv + (pmt / rate) * (factor - 1.0)) / factor;
    }

    /**
     * Calcula el Valor Presente Neto (NPV) para una serie de flujos de efectivo.
     */
    public static double npv(double rate, double... cashFlows) {
        double npv = 0.0;
        for (int t = 0; t < cashFlows.length; t++) {
            npv += cashFlows[t] / Math.pow(1.0 + rate, t);
        }
        return npv;
    }

    /**
     * Calcula la Tasa Interna de Retorno (IRR) mediante aproximación Newton-Raphson.
     */
    public static double irr(double... cashFlows) {
        if (cashFlows == null || cashFlows.length < 2) return 0.0;
        double rate = 0.1; // tasa inicial estimada (10%)
        int maxIter = 100;
        double epsilon = 1e-7;

        for (int i = 0; i < maxIter; i++) {
            double fValue = 0.0;
            double fDerivative = 0.0;
            for (int t = 0; t < cashFlows.length; t++) {
                double den = Math.pow(1.0 + rate, t);
                fValue += cashFlows[t] / den;
                if (t > 0) {
                    fDerivative -= t * cashFlows[t] / Math.pow(1.0 + rate, t + 1);
                }
            }

            if (Math.abs(fValue) < epsilon) {
                return rate;
            }
            if (fDerivative == 0.0) break;
            double nextRate = rate - fValue / fDerivative;
            if (Math.abs(nextRate - rate) < epsilon) {
                return nextRate;
            }
            rate = nextRate;
        }
        return rate;
    }

    /**
     * Calcula el monto final con interés compuesto: A = P * (1 + r/n)^(n*t).
     */
    public static double compoundInterest(double principal, double annualRate, int compoundsPerYear, double years) {
        if (compoundsPerYear <= 0) throw new IllegalArgumentException("compoundsPerYear must be > 0");
        return principal * Math.pow(1.0 + (annualRate / compoundsPerYear), compoundsPerYear * years);
    }

    /**
     * Calcula el monto final con interés simple: A = P * (1 + r*t).
     */
    public static double simpleInterest(double principal, double annualRate, double years) {
        return principal * (1.0 + annualRate * years);
    }

    /**
     * Retorno sobre la inversión (ROI en porcentaje).
     */
    public static double roi(double gainFromInvestment, double costOfInvestment) {
        if (costOfInvestment == 0.0) return 0.0;
        return ((gainFromInvestment - costOfInvestment) / costOfInvestment) * 100.0;
    }

    /**
     * Genera la tabla completa de amortización sistema francés (cuota constante).
     */
    public static List<AmortizationRow> amortizationSchedule(double principal, double annualRate, int periods) {
        List<AmortizationRow> schedule = new ArrayList<>(periods);
        double periodRate = annualRate / 12.0;
        double payment = pmt(periodRate, periods, principal);
        double balance = principal;

        for (int p = 1; p <= periods; p++) {
            double interestPart = balance * periodRate;
            double principalPart = payment - interestPart;
            balance = Math.max(0.0, balance - principalPart);

            schedule.add(new AmortizationRow(p, JettraMath.round(payment, 2), 
                JettraMath.round(principalPart, 2), JettraMath.round(interestPart, 2), JettraMath.round(balance, 2)));
        }

        return schedule;
    }

    /**
     * Capacidad de endeudamiento máxima a partir de una cuota mensual admisible.
     */
    public static double loanAffordability(double monthlyPayment, double annualRate, int years) {
        int nper = years * 12;
        double periodRate = annualRate / 12.0;
        if (periodRate == 0.0) return monthlyPayment * nper;
        double factor = Math.pow(1.0 + periodRate, nper);
        return (monthlyPayment * (factor - 1.0)) / (periodRate * factor);
    }
    /**
     * Tasa de Crecimiento Anual Compuesto (CAGR).
     * @param beginningValue Valor inicial
     * @param endingValue Valor final
     * @param periods Número de períodos (años)
     */
    public static double cagr(double beginningValue, double endingValue, double periods) {
        if (beginningValue <= 0 || endingValue <= 0 || periods <= 0) return 0.0;
        return (Math.pow(endingValue / beginningValue, 1.0 / periods) - 1.0) * 100.0;
    }

    /**
     * Depreciación en línea recta anual.
     */
    public static double depreciationStraightLine(double cost, double salvageValue, int lifeYears) {
        if (lifeYears <= 0) return 0.0;
        return Math.max(0.0, (cost - salvageValue) / lifeYears);
    }

    /**
     * Depreciación por saldo doble decreciente para un período dado (1 a lifeYears).
     */
    public static double depreciationDoubleDeclining(double cost, double salvageValue, int lifeYears, int period) {
        if (lifeYears <= 0 || period <= 0 || cost <= salvageValue) return 0.0;
        double rate = 2.0 / lifeYears;
        double bookValue = cost;
        double dep = 0.0;
        for (int p = 1; p <= period; p++) {
            dep = bookValue * rate;
            if (bookValue - dep < salvageValue) {
                dep = Math.max(0.0, bookValue - salvageValue);
            }
            bookValue -= dep;
            if (bookValue <= salvageValue) break;
        }
        return dep;
    }

    /**
     * Período de recuperación de la inversión (Payback Period en años).
     * @param initialInvestment Inversión inicial (valor positivo)
     * @param annualInflows Flujos de efectivo anuales entrantes
     */
    public static double paybackPeriod(double initialInvestment, double... annualInflows) {
        if (initialInvestment <= 0 || annualInflows == null || annualInflows.length == 0) return 0.0;
        double cumulative = 0.0;
        for (int t = 0; t < annualInflows.length; t++) {
            double inflow = annualInflows[t];
            if (cumulative + inflow >= initialInvestment) {
                double remaining = initialInvestment - cumulative;
                return t + (remaining / inflow);
            }
            cumulative += inflow;
        }
        return -1.0; // No recuperado dentro de los flujos provistos
    }

    /**
     * Tasa Interna de Retorno Modificada (MIRR).
     * @param financeRate Costo de capital / tasa de financiamiento (decimal, ej 0.10)
     * @param reinvestRate Tasa de reinversión para flujos positivos (decimal, ej 0.12)
     * @param cashFlows Serie de flujos con flujos negativos y positivos
     */
    public static double mirr(double financeRate, double reinvestRate, double... cashFlows) {
        if (cashFlows == null || cashFlows.length < 2) return 0.0;
        int n = cashFlows.length - 1;
        double pvNegative = 0.0;
        double fvPositive = 0.0;

        for (int t = 0; t <= n; t++) {
            double cf = cashFlows[t];
            if (cf < 0) {
                pvNegative += cf / Math.pow(1.0 + financeRate, t);
            } else if (cf > 0) {
                fvPositive += cf * Math.pow(1.0 + reinvestRate, n - t);
            }
        }

        if (pvNegative == 0.0 || fvPositive == 0.0) return 0.0;
        return (Math.pow(-fvPositive / pvNegative, 1.0 / n) - 1.0);
    }

    /**
     * Tasa Efectiva Anual (EAR) a partir de una tasa nominal anual.
     */
    public static double effectiveRate(double nominalRate, int periodsPerYear) {
        if (periodsPerYear <= 0) return nominalRate;
        return Math.pow(1.0 + (nominalRate / periodsPerYear), periodsPerYear) - 1.0;
    }
}
