//! Motores Cuantitativos de JettraRDB:
//! - `JettraMath`: Evaluador de expresiones matemáticas y funciones científicas.
//! - `JettraFinance`: Motor financiero cuantitativo (PMT, FV, PV, NPV, IRR, MIRR, ROI, CAGR, Amortización).
//! - `JettraStatistics`: Motor estadístico univariado y bivariado (Media, Mediana, Moda, Varianza, StdDev, Percentiles, Correlación, Regresión Lineal).
//! - `JettraVectorMath`: Álgebra vectorial multidimensional (Producto punto, Coseno, Euclidiana, Manhattan, Chebyshev, Norma, Centroide, Producto Cruz 3D).
//! - `JettraAggregation`: Motor de agrupamiento `GROUP BY` y funciones de agregación sobre colecciones documentales.

use crate::engines::{Document, DocumentEngine};
use crate::json::JsonValue;
use std::collections::{BTreeMap, HashMap};

// ============================================================================
// 1. JettraMath: Motor Matemático y Evaluador de Expresiones
// ============================================================================

pub struct JettraMath;

impl JettraMath {
    pub fn round(x: f64, decimals: i32) -> f64 {
        if x.is_nan() || x.is_infinite() {
            return x;
        }
        let factor = 10_f64.powi(decimals);
        (x * factor).round() / factor
    }

    pub fn clamp(val: f64, min: f64, max: f64) -> f64 {
        val.max(min).min(max)
    }

    pub fn factorial(n: i64) -> Result<i64, String> {
        if n < 0 {
            return Err("Factorial undefined for negative numbers".to_string());
        }
        if n > 20 {
            return Err("Factorial overflow for n > 20".to_string());
        }
        let mut res = 1_i64;
        for i in 2..=n {
            res *= i;
        }
        Ok(res)
    }

    pub fn gcd(mut a: i64, mut b: i64) -> i64 {
        a = a.abs();
        b = b.abs();
        while b != 0 {
            let t = b;
            b = a % b;
            a = t;
        }
        a
    }

    pub fn lcm(a: i64, b: i64) -> i64 {
        if a == 0 || b == 0 {
            return 0;
        }
        (a * (b / Self::gcd(a, b))).abs()
    }

    pub fn eval(expression: &str) -> Result<f64, String> {
        let mut parser = MathParser {
            bytes: expression.as_bytes(),
            pos: 0,
        };
        let val = parser.parse_expr()?;
        parser.skip_ws();
        if parser.pos < parser.bytes.len() {
            return Err(format!(
                "Unexpected character '{}' at pos {}",
                parser.bytes[parser.pos] as char, parser.pos
            ));
        }
        Ok(val)
    }
}

struct MathParser<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> MathParser<'a> {
    fn skip_ws(&mut self) {
        while self.pos < self.bytes.len() && self.bytes[self.pos].is_ascii_whitespace() {
            self.pos += 1;
        }
    }

    fn eat(&mut self, ch: u8) -> bool {
        self.skip_ws();
        if self.pos < self.bytes.len() && self.bytes[self.pos] == ch {
            self.pos += 1;
            true
        } else {
            false
        }
    }

    fn parse_expr(&mut self) -> Result<f64, String> {
        let mut x = self.parse_term()?;
        loop {
            if self.eat(b'+') {
                x += self.parse_term()?;
            } else if self.eat(b'-') {
                x -= self.parse_term()?;
            } else {
                return Ok(x);
            }
        }
    }

    fn parse_term(&mut self) -> Result<f64, String> {
        let mut x = self.parse_factor()?;
        loop {
            if self.eat(b'*') {
                x *= self.parse_factor()?;
            } else if self.eat(b'/') {
                x /= self.parse_factor()?;
            } else if self.eat(b'%') {
                x %= self.parse_factor()?;
            } else {
                return Ok(x);
            }
        }
    }

    fn parse_factor(&mut self) -> Result<f64, String> {
        if self.eat(b'+') {
            return self.parse_factor();
        }
        if self.eat(b'-') {
            return Ok(-self.parse_factor()?);
        }

        self.skip_ws();
        let start = self.pos;
        let mut x: f64;

        if self.eat(b'(') {
            x = self.parse_expr()?;
            if !self.eat(b')') {
                return Err("Missing closing parenthesis".to_string());
            }
        } else if self.pos < self.bytes.len()
            && (self.bytes[self.pos].is_ascii_digit() || self.bytes[self.pos] == b'.')
        {
            while self.pos < self.bytes.len()
                && (self.bytes[self.pos].is_ascii_digit() || self.bytes[self.pos] == b'.')
            {
                self.pos += 1;
            }
            let s = std::str::from_utf8(&self.bytes[start..self.pos]).map_err(|e| e.to_string())?;
            x = s.parse::<f64>().map_err(|e| e.to_string())?;
        } else if self.pos < self.bytes.len() && self.bytes[self.pos].is_ascii_alphabetic() {
            while self.pos < self.bytes.len()
                && (self.bytes[self.pos].is_ascii_alphanumeric() || self.bytes[self.pos] == b'_')
            {
                self.pos += 1;
            }
            let ident = std::str::from_utf8(&self.bytes[start..self.pos])
                .map_err(|e| e.to_string())?
                .to_lowercase();
            if self.eat(b'(') {
                let mut args = Vec::new();
                if !self.eat(b')') {
                    args.push(self.parse_expr()?);
                    while self.eat(b',') {
                        args.push(self.parse_expr()?);
                    }
                    if !self.eat(b')') {
                        return Err(format!("Missing ')' for function {}", ident));
                    }
                }
                x = match ident.as_str() {
                    "sqrt" => args.first().copied().unwrap_or(0.0).sqrt(),
                    "sin" => args.first().copied().unwrap_or(0.0).sin(),
                    "cos" => args.first().copied().unwrap_or(0.0).cos(),
                    "tan" => args.first().copied().unwrap_or(0.0).tan(),
                    "abs" => args.first().copied().unwrap_or(0.0).abs(),
                    "log" | "ln" => args.first().copied().unwrap_or(1.0).ln(),
                    "log10" => args.first().copied().unwrap_or(1.0).log10(),
                    "exp" => args.first().copied().unwrap_or(0.0).exp(),
                    "ceil" => args.first().copied().unwrap_or(0.0).ceil(),
                    "floor" => args.first().copied().unwrap_or(0.0).floor(),
                    "round" => {
                        let v = args.first().copied().unwrap_or(0.0);
                        let d = args.get(1).copied().unwrap_or(0.0) as i32;
                        JettraMath::round(v, d)
                    }
                    "pow" | "power" => args
                        .first()
                        .copied()
                        .unwrap_or(0.0)
                        .powf(args.get(1).copied().unwrap_or(1.0)),
                    "mod" => {
                        args.first().copied().unwrap_or(0.0)
                            % args.get(1).copied().unwrap_or(1.0)
                    }
                    "cbrt" => args.first().copied().unwrap_or(0.0).cbrt(),
                    "hypot" => args
                        .first()
                        .copied()
                        .unwrap_or(0.0)
                        .hypot(args.get(1).copied().unwrap_or(0.0)),
                    "clamp" => JettraMath::clamp(
                        args.first().copied().unwrap_or(0.0),
                        args.get(1).copied().unwrap_or(0.0),
                        args.get(2).copied().unwrap_or(1.0),
                    ),
                    "fact" | "factorial" => {
                        JettraMath::factorial(args.first().copied().unwrap_or(0.0) as i64)? as f64
                    }
                    "gcd" => JettraMath::gcd(
                        args.first().copied().unwrap_or(0.0) as i64,
                        args.get(1).copied().unwrap_or(0.0) as i64,
                    ) as f64,
                    "lcm" => JettraMath::lcm(
                        args.first().copied().unwrap_or(0.0) as i64,
                        args.get(1).copied().unwrap_or(0.0) as i64,
                    ) as f64,
                    _ => return Err(format!("Unknown math function: {}", ident)),
                };
            } else {
                x = match ident.as_str() {
                    "pi" => std::f64::consts::PI,
                    "e" => std::f64::consts::E,
                    _ => return Err(format!("Unknown constant: {}", ident)),
                };
            }
        } else {
            return Err("Invalid math expression".to_string());
        }

        if self.eat(b'^') {
            let exp = self.parse_factor()?;
            x = x.powf(exp);
        }
        Ok(x)
    }
}

// ============================================================================
// 2. JettraFinance: Motor Financiero Cuantitativo
// ============================================================================

#[derive(Debug, Clone, PartialEq)]
pub struct AmortizationRow {
    pub period: usize,
    pub payment: f64,
    pub principal_part: f64,
    pub interest_part: f64,
    pub remaining_balance: f64,
}

pub struct JettraFinance;

impl JettraFinance {
    pub fn pmt(rate: f64, nper: usize, pv: f64) -> f64 {
        if nper == 0 {
            return 0.0;
        }
        if rate == 0.0 {
            return pv / (nper as f64);
        }
        let factor = (1.0 + rate).powi(nper as i32);
        (pv * rate * factor) / (factor - 1.0)
    }

    pub fn fv(rate: f64, nper: usize, pmt: f64, pv: f64) -> f64 {
        if rate == 0.0 {
            return -(pv + pmt * (nper as f64));
        }
        let factor = (1.0 + rate).powi(nper as i32);
        -(pv * factor + (pmt / rate) * (factor - 1.0))
    }

    pub fn pv(rate: f64, nper: usize, pmt: f64, fv: f64) -> f64 {
        if rate == 0.0 {
            return -(fv + pmt * (nper as f64));
        }
        let factor = (1.0 + rate).powi(nper as i32);
        -(fv + (pmt / rate) * (factor - 1.0)) / factor
    }

    pub fn npv(rate: f64, cash_flows: &[f64]) -> f64 {
        cash_flows
            .iter()
            .enumerate()
            .map(|(t, &cf)| cf / (1.0 + rate).powi(t as i32))
            .sum()
    }

    pub fn irr(cash_flows: &[f64]) -> f64 {
        if cash_flows.len() < 2 {
            return 0.0;
        }
        let mut rate = 0.1_f64;
        let epsilon = 1e-7;
        for _ in 0..100 {
            let mut f_val = 0.0;
            let mut f_deriv = 0.0;
            for (t, &cf) in cash_flows.iter().enumerate() {
                let den = (1.0 + rate).powi(t as i32);
                f_val += cf / den;
                if t > 0 {
                    f_deriv -= (t as f64) * cf / (1.0 + rate).powi((t + 1) as i32);
                }
            }
            if f_val.abs() < epsilon {
                return rate;
            }
            if f_deriv == 0.0 {
                break;
            }
            let next = rate - f_val / f_deriv;
            if (next - rate).abs() < epsilon {
                return next;
            }
            rate = next;
        }
        rate
    }

    pub fn mirr(finance_rate: f64, reinvest_rate: f64, cash_flows: &[f64]) -> f64 {
        if cash_flows.len() < 2 {
            return 0.0;
        }
        let n = cash_flows.len() - 1;
        let mut pv_neg = 0.0;
        let mut fv_pos = 0.0;
        for (t, &cf) in cash_flows.iter().enumerate() {
            if cf < 0.0 {
                pv_neg += cf / (1.0 + finance_rate).powi(t as i32);
            } else if cf > 0.0 {
                fv_pos += cf * (1.0 + reinvest_rate).powi((n - t) as i32);
            }
        }
        if pv_neg == 0.0 || fv_pos == 0.0 {
            return 0.0;
        }
        (-fv_pos / pv_neg).powf(1.0 / (n as f64)) - 1.0
    }

    pub fn compound_interest(principal: f64, annual_rate: f64, compounds_per_year: usize, years: f64) -> f64 {
        if compounds_per_year == 0 {
            return principal;
        }
        let n = compounds_per_year as f64;
        principal * (1.0 + annual_rate / n).powf(n * years)
    }

    pub fn simple_interest(principal: f64, annual_rate: f64, years: f64) -> f64 {
        principal * (1.0 + annual_rate * years)
    }

    pub fn roi(gain: f64, cost: f64) -> f64 {
        if cost == 0.0 {
            return 0.0;
        }
        ((gain - cost) / cost) * 100.0
    }

    pub fn cagr(beginning_value: f64, ending_value: f64, periods: f64) -> f64 {
        if beginning_value <= 0.0 || ending_value <= 0.0 || periods <= 0.0 {
            return 0.0;
        }
        ((ending_value / beginning_value).powf(1.0 / periods) - 1.0) * 100.0
    }

    pub fn payback_period(initial_investment: f64, annual_inflows: &[f64]) -> f64 {
        if initial_investment <= 0.0 || annual_inflows.is_empty() {
            return 0.0;
        }
        let mut cumulative = 0.0;
        for (t, &inflow) in annual_inflows.iter().enumerate() {
            if cumulative + inflow >= initial_investment && inflow > 0.0 {
                let rem = initial_investment - cumulative;
                return (t as f64) + (rem / inflow);
            }
            cumulative += inflow;
        }
        -1.0
    }

    pub fn depreciation_straight_line(cost: f64, salvage_value: f64, life_years: usize) -> f64 {
        if life_years == 0 {
            return 0.0;
        }
        ((cost - salvage_value) / (life_years as f64)).max(0.0)
    }

    pub fn amortization_schedule(principal: f64, annual_rate: f64, periods: usize) -> Vec<AmortizationRow> {
        let mut schedule = Vec::with_capacity(periods);
        let period_rate = annual_rate / 12.0;
        let payment = Self::pmt(period_rate, periods, principal);
        let mut balance = principal;

        for p in 1..=periods {
            let interest_part = balance * period_rate;
            let principal_part = payment - interest_part;
            balance = (balance - principal_part).max(0.0);
            schedule.push(AmortizationRow {
                period: p,
                payment: JettraMath::round(payment, 2),
                principal_part: JettraMath::round(principal_part, 2),
                interest_part: JettraMath::round(interest_part, 2),
                remaining_balance: JettraMath::round(balance, 2),
            });
        }
        schedule
    }
}

// ============================================================================
// 3. JettraStatistics: Motor Estadístico Univariado y Bivariado
// ============================================================================

#[derive(Debug, Clone, PartialEq)]
pub struct StatsSummary {
    pub count: usize,
    pub sum: f64,
    pub mean: f64,
    pub median: f64,
    pub mode: f64,
    pub variance: f64,
    pub stddev: f64,
    pub min: f64,
    pub max: f64,
    pub p90: f64,
    pub p95: f64,
    pub p99: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RegressionResult {
    pub slope: f64,
    pub intercept: f64,
    pub r_squared: f64,
}

pub struct JettraStatistics;

impl JettraStatistics {
    pub fn sum(data: &[f64]) -> f64 {
        data.iter().sum()
    }

    pub fn mean(data: &[f64]) -> f64 {
        if data.is_empty() {
            0.0
        } else {
            Self::sum(data) / (data.len() as f64)
        }
    }

    pub fn min(data: &[f64]) -> f64 {
        data.iter().copied().fold(f64::INFINITY, f64::min)
    }

    pub fn max(data: &[f64]) -> f64 {
        data.iter().copied().fold(f64::NEG_INFINITY, f64::max)
    }

    pub fn median(data: &[f64]) -> f64 {
        if data.is_empty() {
            return 0.0;
        }
        let mut sorted = data.to_vec();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        let n = sorted.len();
        if n % 2 == 1 {
            sorted[n / 2]
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }

    pub fn mode(data: &[f64]) -> f64 {
        if data.is_empty() {
            return 0.0;
        }
        let mut freq: HashMap<i64, (usize, f64)> = HashMap::new();
        for &v in data {
            let key = (v * 10000.0).round() as i64;
            let entry = freq.entry(key).or_insert((0, v));
            entry.0 += 1;
        }
        freq.values()
            .max_by_key(|(count, _)| *count)
            .map(|(_, val)| *val)
            .unwrap_or(0.0)
    }

    pub fn variance(data: &[f64], sample: bool) -> f64 {
        let min_len = if sample { 2 } else { 1 };
        if data.len() < min_len {
            return 0.0;
        }
        let avg = Self::mean(data);
        let sum_sq: f64 = data.iter().map(|&v| (v - avg).powi(2)).sum();
        let denom = if sample { data.len() - 1 } else { data.len() };
        sum_sq / (denom as f64)
    }

    pub fn stddev(data: &[f64], sample: bool) -> f64 {
        Self::variance(data, sample).sqrt()
    }

    pub fn percentile(data: &[f64], p: f64) -> f64 {
        if data.is_empty() {
            return 0.0;
        }
        let mut sorted = data.to_vec();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        if sorted.len() == 1 {
            return sorted[0];
        }
        let rank = (p.clamp(0.0, 100.0) / 100.0) * ((sorted.len() - 1) as f64);
        let lower = rank.floor() as usize;
        let upper = rank.ceil() as usize;
        let weight = rank - (lower as f64);
        sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    pub fn iqr(data: &[f64]) -> f64 {
        Self::percentile(data, 75.0) - Self::percentile(data, 25.0)
    }

    pub fn skewness(data: &[f64]) -> f64 {
        if data.len() < 3 {
            return 0.0;
        }
        let n = data.len() as f64;
        let m = Self::mean(data);
        let s = Self::stddev(data, true);
        if s == 0.0 {
            return 0.0;
        }
        let sum_cube: f64 = data.iter().map(|&v| ((v - m) / s).powi(3)).sum();
        (n / ((n - 1.0) * (n - 2.0))) * sum_cube
    }

    pub fn kurtosis(data: &[f64]) -> f64 {
        if data.len() < 4 {
            return 0.0;
        }
        let n = data.len() as f64;
        let m = Self::mean(data);
        let s = Self::stddev(data, true);
        if s == 0.0 {
            return 0.0;
        }
        let sum_quad: f64 = data.iter().map(|&v| ((v - m) / s).powi(4)).sum();
        let f1 = (n * (n + 1.0)) / ((n - 1.0) * (n - 2.0) * (n - 3.0));
        let f2 = (3.0 * (n - 1.0).powi(2)) / ((n - 2.0) * (n - 3.0));
        f1 * sum_quad - f2
    }

    pub fn standard_error(data: &[f64]) -> f64 {
        if data.is_empty() {
            return 0.0;
        }
        Self::stddev(data, true) / (data.len() as f64).sqrt()
    }

    pub fn covariance(x: &[f64], y: &[f64], sample: bool) -> f64 {
        let min_len = if sample { 2 } else { 1 };
        if x.len() != y.len() || x.len() < min_len {
            return 0.0;
        }
        let mx = Self::mean(x);
        let my = Self::mean(y);
        let sum: f64 = x.iter().zip(y.iter()).map(|(&xi, &yi)| (xi - mx) * (yi - my)).sum();
        let denom = if sample { x.len() - 1 } else { x.len() };
        sum / (denom as f64)
    }

    pub fn correlation(x: &[f64], y: &[f64]) -> f64 {
        let cov = Self::covariance(x, y, true);
        let sx = Self::stddev(x, true);
        let sy = Self::stddev(y, true);
        if sx == 0.0 || sy == 0.0 {
            0.0
        } else {
            cov / (sx * sy)
        }
    }

    pub fn linear_regression(x: &[f64], y: &[f64]) -> RegressionResult {
        if x.len() != y.len() || x.len() < 2 {
            return RegressionResult {
                slope: 0.0,
                intercept: 0.0,
                r_squared: 0.0,
            };
        }
        let mx = Self::mean(x);
        let my = Self::mean(y);
        let mut num = 0.0;
        let mut den = 0.0;
        for (&xi, &yi) in x.iter().zip(y.iter()) {
            let dx = xi - mx;
            let dy = yi - my;
            num += dx * dy;
            den += dx * dx;
        }
        let slope = if den != 0.0 { num / den } else { 0.0 };
        let intercept = my - slope * mx;
        let r = Self::correlation(x, y);
        RegressionResult {
            slope,
            intercept,
            r_squared: r * r,
        }
    }

    pub fn summary(data: &[f64]) -> StatsSummary {
        if data.is_empty() {
            return StatsSummary {
                count: 0,
                sum: 0.0,
                mean: 0.0,
                median: 0.0,
                mode: 0.0,
                variance: 0.0,
                stddev: 0.0,
                min: 0.0,
                max: 0.0,
                p90: 0.0,
                p95: 0.0,
                p99: 0.0,
            };
        }
        let sum = Self::sum(data);
        let var = Self::variance(data, true);
        StatsSummary {
            count: data.len(),
            sum,
            mean: sum / (data.len() as f64),
            median: Self::median(data),
            mode: Self::mode(data),
            variance: var,
            stddev: var.sqrt(),
            min: Self::min(data),
            max: Self::max(data),
            p90: Self::percentile(data, 90.0),
            p95: Self::percentile(data, 95.0),
            p99: Self::percentile(data, 99.0),
        }
    }
}

// ============================================================================
// 4. JettraVectorMath: Álgebra Vectorial Multidimensional
// ============================================================================

pub struct JettraVectorMath;

impl JettraVectorMath {
    pub fn dot_product(v1: &[f32], v2: &[f32]) -> Result<f32, String> {
        if v1.len() != v2.len() {
            return Err("Vector dimension mismatch".to_string());
        }
        Ok(v1.iter().zip(v2.iter()).map(|(a, b)| a * b).sum())
    }

    pub fn norm(v: &[f32]) -> f32 {
        v.iter().map(|x| x * x).sum::<f32>().sqrt()
    }

    pub fn l1_norm(v: &[f32]) -> f32 {
        v.iter().map(|x| x.abs()).sum()
    }

    pub fn cosine_similarity(v1: &[f32], v2: &[f32]) -> Result<f32, String> {
        let dot = Self::dot_product(v1, v2)?;
        let n1 = Self::norm(v1);
        let n2 = Self::norm(v2);
        if n1 == 0.0 || n2 == 0.0 {
            Ok(0.0)
        } else {
            Ok(dot / (n1 * n2))
        }
    }

    pub fn euclidean_distance(v1: &[f32], v2: &[f32]) -> Result<f32, String> {
        if v1.len() != v2.len() {
            return Err("Vector dimension mismatch".to_string());
        }
        let sum_sq: f32 = v1.iter().zip(v2.iter()).map(|(a, b)| (a - b).powi(2)).sum();
        Ok(sum_sq.sqrt())
    }

    pub fn manhattan_distance(v1: &[f32], v2: &[f32]) -> Result<f32, String> {
        if v1.len() != v2.len() {
            return Err("Vector dimension mismatch".to_string());
        }
        Ok(v1.iter().zip(v2.iter()).map(|(a, b)| (a - b).abs()).sum())
    }

    pub fn chebyshev_distance(v1: &[f32], v2: &[f32]) -> Result<f32, String> {
        if v1.len() != v2.len() {
            return Err("Vector dimension mismatch".to_string());
        }
        Ok(v1
            .iter()
            .zip(v2.iter())
            .map(|(a, b)| (a - b).abs())
            .fold(0.0_f32, f32::max))
    }

    pub fn normalize(v: &[f32]) -> Vec<f32> {
        let n = Self::norm(v);
        if n == 0.0 {
            v.to_vec()
        } else {
            v.iter().map(|&x| x / n).collect()
        }
    }

    pub fn centroid(vectors: &[Vec<f32>]) -> Result<Vec<f32>, String> {
        if vectors.is_empty() {
            return Ok(Vec::new());
        }
        let dim = vectors[0].len();
        let mut avg = vec![0.0_f32; dim];
        for v in vectors {
            if v.len() != dim {
                return Err("Vectors in centroid must share the same dimension".to_string());
            }
            for (i, &val) in v.iter().enumerate() {
                avg[i] += val;
            }
        }
        let count = vectors.len() as f32;
        for val in &mut avg {
            *val /= count;
        }
        Ok(avg)
    }

    pub fn cross_product(v1: &[f32], v2: &[f32]) -> Result<Vec<f32>, String> {
        if v1.len() != 3 || v2.len() != 3 {
            return Err("Cross product requires 3D vectors".to_string());
        }
        Ok(vec![
            v1[1] * v2[2] - v1[2] * v2[1],
            v1[2] * v2[0] - v1[0] * v2[2],
            v1[0] * v2[1] - v1[1] * v2[0],
        ])
    }

    pub fn add(v1: &[f32], v2: &[f32]) -> Result<Vec<f32>, String> {
        if v1.len() != v2.len() {
            return Err("Vector dimension mismatch".to_string());
        }
        Ok(v1.iter().zip(v2.iter()).map(|(a, b)| a + b).collect())
    }

    pub fn subtract(v1: &[f32], v2: &[f32]) -> Result<Vec<f32>, String> {
        if v1.len() != v2.len() {
            return Err("Vector dimension mismatch".to_string());
        }
        Ok(v1.iter().zip(v2.iter()).map(|(a, b)| a - b).collect())
    }
}

// ============================================================================
// 5. JettraAggregation: Motor de Agregaciones y GROUP BY
// ============================================================================

#[derive(Debug, Clone)]
pub struct AggregateSpec {
    pub function: String,
    pub field: String,
    pub alias: Option<String>,
}

impl AggregateSpec {
    pub fn new(function: impl Into<String>, field: impl Into<String>, alias: Option<String>) -> Self {
        Self {
            function: function.into().to_uppercase(),
            field: field.into(),
            alias,
        }
    }

    pub fn output_name(&self) -> String {
        if let Some(a) = &self.alias {
            if !a.is_empty() {
                return a.clone();
            }
        }
        format!("{}({})", self.function, self.field)
    }
}

#[derive(Debug, Clone)]
pub struct AggregationResult {
    pub column_headers: Vec<String>,
    pub rows: Vec<BTreeMap<String, JsonValue>>,
    pub total_groups: usize,
}

pub struct JettraAggregation;

impl JettraAggregation {
    pub fn aggregate(
        engine: &DocumentEngine,
        group_by_fields: &[String],
        specs: &[AggregateSpec],
        where_filter: Option<&HashMap<String, String>>,
    ) -> AggregationResult {
        let mut headers = group_by_fields.to_vec();
        for s in specs {
            headers.push(s.output_name());
        }

        let docs = engine.find_all();
        if docs.is_empty() {
            return AggregationResult {
                column_headers: headers,
                rows: Vec::new(),
                total_groups: 0,
            };
        }

        let mut groups: BTreeMap<String, Vec<Document>> = BTreeMap::new();
        for doc in docs {
            if let Some(filter) = where_filter {
                let mut ok = true;
                for (fk, fv) in filter {
                    let doc_val = doc.get(fk).map(|v| v.to_string_value()).unwrap_or_default();
                    if !doc_val.eq_ignore_ascii_case(fv) {
                        ok = false;
                        break;
                    }
                }
                if !ok {
                    continue;
                }
            }

            let key = if group_by_fields.is_empty() {
                "__ALL__".to_string()
            } else {
                group_by_fields
                    .iter()
                    .map(|col| doc.get(col).map(|v| v.to_string_value()).unwrap_or_default())
                    .collect::<Vec<_>>()
                    .join("###")
            };
            groups.entry(key).or_default().push(doc);
        }

        let mut result_rows = Vec::with_capacity(groups.len());
        for (_gkey, bucket) in groups {
            let first = &bucket[0];
            let mut row = BTreeMap::new();
            for col in group_by_fields {
                if let Some(v) = first.get(col) {
                    row.insert(col.clone(), v.clone());
                } else {
                    row.insert(col.clone(), JsonValue::Null);
                }
            }
            for spec in specs {
                let val = Self::compute_spec(&spec.function, &spec.field, &bucket);
                row.insert(spec.output_name(), val);
            }
            result_rows.push(row);
        }

        let total = result_rows.len();
        AggregationResult {
            column_headers: headers,
            rows: result_rows,
            total_groups: total,
        }
    }

    fn compute_spec(func: &str, field: &str, bucket: &[Document]) -> JsonValue {
        if func == "COUNT" {
            if field == "*" || field.is_empty() {
                return JsonValue::Number(bucket.len() as f64);
            }
            let c = bucket.iter().filter(|d| d.contains_key(field)).count();
            return JsonValue::Number(c as f64);
        }

        let values: Vec<f64> = bucket
            .iter()
            .filter_map(|d| d.get(field).and_then(|v| v.as_f64()))
            .collect();

        if values.is_empty() {
            return JsonValue::Number(0.0);
        }

        let res = match func {
            "SUM" => JettraMath::round(JettraStatistics::sum(&values), 4),
            "AVG" | "AVERAGE" | "MEAN" => JettraMath::round(JettraStatistics::mean(&values), 4),
            "MIN" => JettraStatistics::min(&values),
            "MAX" => JettraStatistics::max(&values),
            "MEDIAN" | "MED" => JettraMath::round(JettraStatistics::median(&values), 4),
            "MODE" => JettraMath::round(JettraStatistics::mode(&values), 4),
            "RANGE" => JettraMath::round(
                JettraStatistics::max(&values) - JettraStatistics::min(&values),
                4,
            ),
            "IQR" => JettraMath::round(JettraStatistics::iqr(&values), 4),
            "STDDEV" | "STD" => JettraMath::round(JettraStatistics::stddev(&values, true), 4),
            "VARIANCE" | "VAR" => JettraMath::round(JettraStatistics::variance(&values, true), 4),
            "SKEWNESS" | "SKEW" => JettraMath::round(JettraStatistics::skewness(&values), 4),
            "KURTOSIS" | "KURT" => JettraMath::round(JettraStatistics::kurtosis(&values), 4),
            "P50" => JettraMath::round(JettraStatistics::percentile(&values, 50.0), 4),
            "P90" => JettraMath::round(JettraStatistics::percentile(&values, 90.0), 4),
            "P95" => JettraMath::round(JettraStatistics::percentile(&values, 95.0), 4),
            "P99" => JettraMath::round(JettraStatistics::percentile(&values, 99.0), 4),
            "FIRST" => values.first().copied().unwrap_or(0.0),
            "LAST" => values.last().copied().unwrap_or(0.0),
            _ => 0.0,
        };
        JsonValue::Number(res)
    }
}

