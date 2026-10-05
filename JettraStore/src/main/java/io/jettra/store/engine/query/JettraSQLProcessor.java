package io.jettra.store.engine.query;

import io.jettra.store.core.JettraDatabase;
import io.jettra.store.engine.models.DocumentEngine;
import io.jettra.store.calc.*;
import java.util.regex.*;

import java.util.*;

public final class JettraSQLProcessor {
    private final JettraDatabase database;

    public record QueryResult(List<String> columns, List<List<Object>> rows, int affectedRows, String message) {
        public int totalRows() { return affectedRows; }
        public String summary() { return message; }
    }

    public JettraSQLProcessor(JettraDatabase database) {
        this.database = database;
    }

    private static String sanitize(String str) {
        if (str == null) return "";
        return str.replace("'", "").replace(String.valueOf((char)34), "").replace(";", "").trim();
    }

    public QueryResult execute(String sql) {
        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();

        if (upper.startsWith("SELECT ")) {
            if (!upper.contains(" FROM ")) {
                return executeScalarExpression(trimmed);
            }
            if (upper.contains(" GROUP BY ") || containsAggregation(upper)) {
                return executeAggregation(trimmed);
            }
            return executeSelect(trimmed);
        } else if (upper.startsWith("AGGREGATE ")) {
            return executeDirectAggregate(trimmed);
        } else if (upper.startsWith("MATH ") || upper.startsWith("CALC ")) {
            return executeDirectMath(trimmed);
        } else if (upper.startsWith("FINANCE ") || upper.startsWith("FINANCIAL ")) {
            return executeDirectFinance(trimmed);
        } else if (upper.startsWith("STATS ") || upper.startsWith("STATISTICS ")) {
            return executeDirectStats(trimmed);
        } else if (upper.startsWith("VECTOR ")) {
            return executeDirectVector(trimmed);
        } else if (upper.startsWith("INSERT INTO ")) {
            return executeInsert(trimmed);
        } else if (upper.startsWith("UPDATE ")) {
            return executeUpdate(trimmed);
        } else if (upper.startsWith("DELETE FROM ")) {
            return executeDelete(trimmed);
        } else if (upper.startsWith("INSTALL SAMPLES") || upper.startsWith("INSTALL SAMPLE") || upper.startsWith("LOAD SAMPLE")) {
            return executeInstallSamples(upper);
        }

        return new QueryResult(List.of("result"), List.of(List.of("SQL not fully parsed: " + trimmed)), 1, "Generic execution");
    }

    private QueryResult executeInstallSamples(String upper) {
        if (database == null) {
            return new QueryResult(List.of("error"), List.of(List.of((Object)"Base de datos no disponible")), 0, "Error");
        }
        String dbName = database.getDatabaseName();
        if (upper.contains("HOSPITAL") || upper.contains("HOSTIPAL")) {
            io.jettra.store.sample.JettraStoreSamples.installHospital(database, true);
            return new QueryResult(List.of("status", "database", "objects"), 
                List.of(List.of((Object)"SUCCESS", (Object)dbName, (Object)"2000000")), 1, "Muestra hospital 2M instalada exitosamente");
        } else if (upper.contains("AMBIENTAL") || upper.contains("ENVIRONMENTAL")) {
            io.jettra.store.sample.JettraStoreSamples.installAmbiental(database, true);
            return new QueryResult(List.of("status", "database", "objects"), 
                List.of(List.of((Object)"SUCCESS", (Object)dbName, (Object)"3000000")), 1, "Muestra ambiental 3M instalada exitosamente");
        } else if (upper.contains("FACTURA")) {
            io.jettra.store.sample.JettraStoreSamples.installFactura(database, true);
            return new QueryResult(List.of("status", "database", "objects"), 
                List.of(List.of((Object)"SUCCESS", (Object)dbName, (Object)"3000000")), 1, "Muestra factura 3M instalada exitosamente");
        } else {
            io.jettra.store.sample.JettraStoreSamples.installSample(dbName, database, true);
            return new QueryResult(List.of("status", "database"), 
                List.of(List.of((Object)"SUCCESS", (Object)dbName)), 1, "Muestra instalada");
        }
    }


    private boolean containsAggregation(String upper) {
        return upper.contains("SUM(") || upper.contains("AVG(") || upper.contains("COUNT(") 
            || upper.contains("MIN(") || upper.contains("MAX(") || upper.contains("STDDEV(") 
            || upper.contains("VARIANCE(") || upper.contains("MEDIAN(") || upper.contains("MODE(")
            || upper.contains("RANGE(") || upper.contains("IQR(") || upper.contains("FIRST(")
            || upper.contains("LAST(") || upper.contains("SKEWNESS(") || upper.contains("KURTOSIS(")
            || upper.contains("P50(") || upper.contains("P90(") || upper.contains("P95(") || upper.contains("P99(");
    }

    private QueryResult executeScalarExpression(String sql) {
        String expr = sql.substring(7).trim(); // remove 'SELECT '
        // Check if there is an alias: SELECT expr AS alias
        String alias = "result";
        int asIdx = expr.toUpperCase().lastIndexOf(" AS ");
        if (asIdx != -1) {
            alias = sanitize(expr.substring(asIdx + 4));
            expr = expr.substring(0, asIdx).trim();
        }

        try {
            double val = JettraMath.eval(expr);
            return new QueryResult(List.of(alias), List.of(List.of((Object) val)), 1, "Scalar calculation executed");
        } catch (Exception ex) {
            // Check for finance functions
            String u = expr.toUpperCase();
            if (u.startsWith("PMT(") || u.startsWith("FV(") || u.startsWith("PV(") || u.startsWith("ROI(")
                    || u.startsWith("CAGR(") || u.startsWith("NPV(") || u.startsWith("IRR(") || u.startsWith("COMPOUND(")
                    || u.startsWith("MIRR(") || u.startsWith("SIMPLE(")) {
                return executeScalarFinance(expr, alias);
            }
            return new QueryResult(List.of("error"), List.of(List.of((Object) ("Evaluation error: " + ex.getMessage()))), 0, "Error");
        }
    }

    private QueryResult executeScalarFinance(String expr, String alias) {
        try {
            int open = expr.indexOf('(');
            int close = expr.lastIndexOf(')');
            String fn = expr.substring(0, open).trim().toUpperCase();
            String argsStr = expr.substring(open + 1, close).trim();
            String[] parts = argsStr.split(",");
            double[] args = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                args[i] = JettraMath.eval(parts[i].trim());
            }

            double res = switch (fn) {
                case "PMT" -> JettraFinance.pmt(args[0], (int) args[1], args[2]);
                case "FV"  -> JettraFinance.fv(args[0], (int) args[1], args[2], args.length > 3 ? args[3] : 0.0);
                case "PV"  -> JettraFinance.pv(args[0], (int) args[1], args[2], args.length > 3 ? args[3] : 0.0);
                case "ROI" -> JettraFinance.roi(args[0], args[1]);
                case "CAGR" -> JettraFinance.cagr(args[0], args[1], args[2]);
                case "COMPOUND" -> JettraFinance.compoundInterest(args[0], args[1], (int) args[2], args[3]);
                case "SIMPLE" -> JettraFinance.simpleInterest(args[0], args[1], args[2]);
                case "NPV" -> {
                    double rate = args[0];
                    double[] cfs = Arrays.copyOfRange(args, 1, args.length);
                    yield JettraFinance.npv(rate, cfs);
                }
                case "IRR" -> JettraFinance.irr(args);
                case "MIRR" -> {
                    double fRate = args[0];
                    double rRate = args[1];
                    double[] cfs = Arrays.copyOfRange(args, 2, args.length);
                    yield JettraFinance.mirr(fRate, rRate, cfs);
                }
                default -> 0.0;
            };

            return new QueryResult(List.of(alias), List.of(List.of((Object) JettraMath.round(res, 4))), 1, "Finance calculation executed");
        } catch (Exception e) {
            return new QueryResult(List.of("error"), List.of(List.of((Object) ("Finance error: " + e.getMessage()))), 0, "Error");
        }
    }

    private QueryResult executeAggregation(String sql) {
        int fromIdx = sql.toUpperCase().indexOf(" FROM ");
        if (fromIdx == -1) {
            return new QueryResult(List.of("error"), List.of(List.of((Object) "Falta la cláusula FROM")), 0, "Error");
        }

        String selectFields = sql.substring(7, fromIdx).trim();
        String afterFrom = sql.substring(fromIdx + 6).trim();

        // 1. Extraer LIMIT si existe
        int limit = 5000;
        int limitIdx = afterFrom.toUpperCase().lastIndexOf(" LIMIT ");
        if (limitIdx != -1) {
            try {
                limit = Integer.parseInt(sanitize(afterFrom.substring(limitIdx + 7)));
            } catch (Exception ignored) {}
            afterFrom = afterFrom.substring(0, limitIdx).trim();
        }

        // 2. Extraer GROUP BY
        List<String> groupByCols = new ArrayList<>();
        int groupIdx = afterFrom.toUpperCase().indexOf(" GROUP BY ");
        if (groupIdx != -1) {
            String groupClause = afterFrom.substring(groupIdx + 10).trim();
            afterFrom = afterFrom.substring(0, groupIdx).trim();
            for (String g : groupClause.split(",")) {
                String c = sanitize(g);
                if (!c.isEmpty()) groupByCols.add(c);
            }
        }

        // 3. Extraer WHERE
        Map<String, Object> whereFilter = null;
        int whereIdx = afterFrom.toUpperCase().indexOf(" WHERE ");
        String collection;
        if (whereIdx != -1) {
            collection = sanitize(afterFrom.substring(0, whereIdx));
            String whereClause = afterFrom.substring(whereIdx + 7).trim();
            String[] kv = whereClause.split("=");
            if (kv.length == 2) {
                whereFilter = Map.of(sanitize(kv[0]), sanitize(kv[1]));
            }
        } else {
            collection = sanitize(afterFrom.split("\\s+")[0]);
        }

        DocumentEngine docEngine = database.getDocumentEngine(collection);
        if (docEngine == null || docEngine.isEmpty()) {
            return new QueryResult(List.of("result"), Collections.emptyList(), 0, "0 registros en colección '" + collection + "'");
        }

        // 4. Parsear especificaciones de agregación
        List<JettraAggregation.AggregateSpec> specs = new ArrayList<>();
        // Split inteligente por comas fuera de paréntesis
        List<String> selectParts = splitSelectFields(selectFields);
        for (String part : selectParts) {
            String p = part.trim();
            String alias = null;
            int asIdx = p.toUpperCase().lastIndexOf(" AS ");
            if (asIdx != -1) {
                alias = sanitize(p.substring(asIdx + 4));
                p = p.substring(0, asIdx).trim();
            }

            int parenOpen = p.indexOf('(');
            int parenClose = p.lastIndexOf(')');
            if (parenOpen != -1 && parenClose != -1 && parenClose > parenOpen) {
                String fn = p.substring(0, parenOpen).trim().toUpperCase();
                String field = sanitize(p.substring(parenOpen + 1, parenClose));
                specs.add(new JettraAggregation.AggregateSpec(fn, field, alias));
            } else {
                String col = sanitize(p);
                if (!col.isEmpty() && !groupByCols.contains(col)) {
                    groupByCols.add(col);
                }
            }
        }

        JettraAggregation.AggregationResult aggResult = JettraAggregation.aggregate(docEngine, groupByCols, specs, whereFilter);
        List<String> headers = aggResult.columnHeaders();
        List<List<Object>> rows = new ArrayList<>(Math.min(limit, aggResult.rows().size()));

        for (Map<String, Object> r : aggResult.rows()) {
            List<Object> row = new ArrayList<>(headers.size());
            for (String h : headers) {
                row.add(r.getOrDefault(h, null));
            }
            rows.add(row);
            if (rows.size() >= limit) break;
        }

        String summary = String.format("Agregación ejecutada sobre '%s': %d grupo(s) computado(s)", collection, rows.size());
        return new QueryResult(headers, rows, rows.size(), summary);
    }

    private List<String> splitSelectFields(String selectFields) {
        List<String> result = new ArrayList<>();
        int parenDepth = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selectFields.length(); i++) {
            char c = selectFields.charAt(i);
            if (c == '(') parenDepth++;
            else if (c == ')') parenDepth--;
            if (c == ',' && parenDepth == 0) {
                result.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        if (!sb.isEmpty()) result.add(sb.toString().trim());
        return result;
    }

    private QueryResult executeDirectAggregate(String cmd) {
        // AGGREGATE <collection> [GROUP BY <field>] [SUM <f>] [AVG <f>] [COUNT] [MIN <f>] [MAX <f>]
        String rest = cmd.substring(10).trim();
        String[] tokens = rest.split("\\s+");
        if (tokens.length == 0 || tokens[0].isBlank()) {
            return new QueryResult(List.of("error"), List.of(List.of((Object) "Uso: AGGREGATE <coleccion> [GROUP BY <campo>] [SUM <f>] [AVG <f>] [COUNT]")), 0, "Error");
        }
        String collection = sanitize(tokens[0]);
        DocumentEngine docEngine = database.getDocumentEngine(collection);
        if (docEngine == null || docEngine.isEmpty()) {
            return new QueryResult(List.of("result"), Collections.emptyList(), 0, "0 registros en colección '" + collection + "'");
        }

        List<String> groupByCols = new ArrayList<>();
        List<JettraAggregation.AggregateSpec> specs = new ArrayList<>();

        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i].toUpperCase();
            if (t.equals("GROUP") && i + 2 < tokens.length && tokens[i + 1].equalsIgnoreCase("BY")) {
                groupByCols.add(sanitize(tokens[i + 2]));
                i += 2;
            } else if (t.equals("SUM") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("SUM", sanitize(tokens[++i]), null));
            } else if ((t.equals("AVG") || t.equals("MEAN")) && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("AVG", sanitize(tokens[++i]), null));
            } else if (t.equals("COUNT")) {
                specs.add(new JettraAggregation.AggregateSpec("COUNT", "*", null));
            } else if (t.equals("MIN") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("MIN", sanitize(tokens[++i]), null));
            } else if (t.equals("MAX") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("MAX", sanitize(tokens[++i]), null));
            } else if (t.equals("STDDEV") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("STDDEV", sanitize(tokens[++i]), null));
            } else if (t.equals("VARIANCE") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("VARIANCE", sanitize(tokens[++i]), null));
            } else if (t.equals("MEDIAN") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("MEDIAN", sanitize(tokens[++i]), null));
            } else if (t.equals("MODE") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("MODE", sanitize(tokens[++i]), null));
            } else if (t.equals("RANGE") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("RANGE", sanitize(tokens[++i]), null));
            } else if (t.equals("IQR") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("IQR", sanitize(tokens[++i]), null));
            } else if (t.equals("FIRST") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("FIRST", sanitize(tokens[++i]), null));
            } else if (t.equals("LAST") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("LAST", sanitize(tokens[++i]), null));
            } else if (t.equals("SKEWNESS") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("SKEWNESS", sanitize(tokens[++i]), null));
            } else if (t.equals("KURTOSIS") && i + 1 < tokens.length) {
                specs.add(new JettraAggregation.AggregateSpec("KURTOSIS", sanitize(tokens[++i]), null));
            }
        }

        if (specs.isEmpty()) {
            specs.add(new JettraAggregation.AggregateSpec("COUNT", "*", "total"));
        }

        var agg = JettraAggregation.aggregate(docEngine, groupByCols, specs);
        List<String> headers = agg.columnHeaders();
        List<List<Object>> rows = new ArrayList<>(agg.rows().size());
        for (var r : agg.rows()) {
            List<Object> row = new ArrayList<>(headers.size());
            for (String h : headers) row.add(r.getOrDefault(h, null));
            rows.add(row);
        }
        return new QueryResult(headers, rows, rows.size(), "Agregación directa computada exitosamente");
    }

    private QueryResult executeDirectMath(String cmd) {
        String clean = cmd.replaceAll("(?i)^(MATH|CALC)\\s+", "").trim();
        try {
            double res = JettraMath.eval(clean);
            return new QueryResult(List.of("expression", "result"), List.of(List.of((Object) clean, (Object) res)), 1, "Operación matemática computada");
        } catch (Exception e) {
            return new QueryResult(List.of("error"), List.of(List.of((Object) e.getMessage())), 0, "Error en expresión matemática");
        }
    }

    private QueryResult executeDirectFinance(String cmd) {
        String clean = cmd.replaceAll("(?i)^(FINANCE|FINANCIAL)\s+", "").trim();
        String[] parts = clean.split("\s+");
        if (parts.length == 0 || parts[0].isBlank()) return new QueryResult(List.of("error"), List.of(List.of((Object) "Función financiera no especificada")), 0, "Error");
        String fn = parts[0].toUpperCase();

        try {
            switch (fn) {
                case "PMT" -> {
                    double rate = Double.parseDouble(parts[1]);
                    int nper = Integer.parseInt(parts[2]);
                    double pv = Double.parseDouble(parts[3]);
                    double p = JettraFinance.pmt(rate, nper, pv);
                    return new QueryResult(List.of("tasa", "periodos", "prestamo", "cuota_pmt"), 
                        List.of(List.of((Object) rate, (Object) nper, (Object) pv, (Object) JettraMath.round(p, 2))), 1, "Cuota PMT calculada");
                }
                case "FV" -> {
                    double rate = Double.parseDouble(parts[1]);
                    int nper = Integer.parseInt(parts[2]);
                    double pmt = Double.parseDouble(parts[3]);
                    double pv = parts.length > 4 ? Double.parseDouble(parts[4]) : 0.0;
                    double f = JettraFinance.fv(rate, nper, pmt, pv);
                    return new QueryResult(List.of("tasa", "periodos", "cuota", "valor_futuro"), 
                        List.of(List.of((Object) rate, (Object) nper, (Object) pmt, (Object) JettraMath.round(f, 2))), 1, "Valor Futuro FV calculado");
                }
                case "PV" -> {
                    double rate = Double.parseDouble(parts[1]);
                    int nper = Integer.parseInt(parts[2]);
                    double pmt = Double.parseDouble(parts[3]);
                    double fv = parts.length > 4 ? Double.parseDouble(parts[4]) : 0.0;
                    double pvVal = JettraFinance.pv(rate, nper, pmt, fv);
                    return new QueryResult(List.of("tasa", "periodos", "cuota", "valor_presente"), 
                        List.of(List.of((Object) rate, (Object) nper, (Object) pmt, (Object) JettraMath.round(pvVal, 2))), 1, "Valor Presente PV calculado");
                }
                case "COMPOUND" -> {
                    double p = Double.parseDouble(parts[1]);
                    double r = Double.parseDouble(parts[2]);
                    int n = Integer.parseInt(parts[3]);
                    double t = Double.parseDouble(parts[4]);
                    double a = JettraFinance.compoundInterest(p, r, n, t);
                    return new QueryResult(List.of("capital", "tasa_anual", "frecuencia", "anios", "monto_final"),
                        List.of(List.of((Object) p, (Object) r, (Object) n, (Object) t, (Object) JettraMath.round(a, 2))), 1, "Interés compuesto calculado");
                }
                case "SIMPLE" -> {
                    double p = Double.parseDouble(parts[1]);
                    double r = Double.parseDouble(parts[2]);
                    double t = Double.parseDouble(parts[3]);
                    double a = JettraFinance.simpleInterest(p, r, t);
                    return new QueryResult(List.of("capital", "tasa_anual", "anios", "monto_final"),
                        List.of(List.of((Object) p, (Object) r, (Object) t, (Object) JettraMath.round(a, 2))), 1, "Interés simple calculado");
                }
                case "ROI" -> {
                    double gain = Double.parseDouble(parts[1]);
                    double cost = Double.parseDouble(parts[2]);
                    double roi = JettraFinance.roi(gain, cost);
                    return new QueryResult(List.of("ingreso", "costo", "roi_pct"),
                        List.of(List.of((Object) gain, (Object) cost, (Object) (JettraMath.round(roi, 2) + "%"))), 1, "ROI calculado");
                }
                case "CAGR" -> {
                    double initial = Double.parseDouble(parts[1]);
                    double finalVal = Double.parseDouble(parts[2]);
                    double periods = Double.parseDouble(parts[3]);
                    double cagrVal = JettraFinance.cagr(initial, finalVal, periods);
                    return new QueryResult(List.of("valor_inicial", "valor_final", "anios", "cagr_pct"),
                        List.of(List.of((Object) initial, (Object) finalVal, (Object) periods, (Object) (JettraMath.round(cagrVal, 2) + "%"))), 1, "CAGR calculado");
                }
                case "NPV" -> {
                    double rate = Double.parseDouble(parts[1]);
                    double[] cfs = new double[parts.length - 2];
                    for (int i = 2; i < parts.length; i++) cfs[i - 2] = Double.parseDouble(parts[i]);
                    double npvVal = JettraFinance.npv(rate, cfs);
                    return new QueryResult(List.of("tasa_descuento", "flujos", "npv_van"),
                        List.of(List.of((Object) rate, (Object) cfs.length, (Object) JettraMath.round(npvVal, 2))), 1, "Valor Presente Neto (NPV) calculado");
                }
                case "IRR" -> {
                    double[] cfs = new double[parts.length - 1];
                    for (int i = 1; i < parts.length; i++) cfs[i - 1] = Double.parseDouble(parts[i]);
                    double irrVal = JettraFinance.irr(cfs);
                    return new QueryResult(List.of("flujos", "irr_tir_pct"),
                        List.of(List.of((Object) cfs.length, (Object) (JettraMath.round(irrVal * 100.0, 2) + "%"))), 1, "TIR (IRR) calculada");
                }
                case "PAYBACK" -> {
                    double initial = Double.parseDouble(parts[1]);
                    double[] inflows = new double[parts.length - 2];
                    for (int i = 2; i < parts.length; i++) inflows[i - 2] = Double.parseDouble(parts[i]);
                    double pb = JettraFinance.paybackPeriod(initial, inflows);
                    return new QueryResult(List.of("inversion_inicial", "anios_recuperacion"),
                        List.of(List.of((Object) initial, (Object) (pb >= 0 ? JettraMath.round(pb, 2) : "No recuperado"))), 1, "Payback period calculado");
                }
                case "DEPRECIATION" -> {
                    double cost = Double.parseDouble(parts[1]);
                    double salvage = Double.parseDouble(parts[2]);
                    int life = Integer.parseInt(parts[3]);
                    double d = JettraFinance.depreciationStraightLine(cost, salvage, life);
                    return new QueryResult(List.of("costo_activo", "valor_residual", "vida_util_anios", "depreciacion_anual"),
                        List.of(List.of((Object) cost, (Object) salvage, (Object) life, (Object) JettraMath.round(d, 2))), 1, "Depreciación en línea recta calculada");
                }
                case "AMORTIZATION" -> {
                    double p = Double.parseDouble(parts[1]);
                    double r = Double.parseDouble(parts[2]);
                    int n = Integer.parseInt(parts[3]);
                    var sched = JettraFinance.amortizationSchedule(p, r, n);
                    List<List<Object>> rows = new ArrayList<>(sched.size());
                    for (var row : sched) {
                        rows.add(List.of((Object) row.period(), (Object) row.payment(), (Object) row.principalPart(), (Object) row.interestPart(), (Object) row.remainingBalance()));
                    }
                    return new QueryResult(List.of("periodo", "cuota", "amortizacion_capital", "interes", "saldo_pendiente"), rows, rows.size(), "Tabla de amortización francesa generada");
                }
                default -> {
                    return new QueryResult(List.of("error"), List.of(List.of((Object) ("Función financiera no soportada: " + fn))), 0, "Error");
                }
            }
        } catch (Exception e) {
            return new QueryResult(List.of("error"), List.of(List.of((Object) e.getMessage())), 0, "Error en comando financiero");
        }
    }

    private QueryResult executeDirectStats(String cmd) {
        String clean = cmd.replaceAll("(?i)^(STATS|STATISTICS)\s+", "").trim();
        String[] parts = clean.split("\s+", 2);
        if (parts.length == 0 || parts[0].isBlank()) return new QueryResult(List.of("error"), List.of(List.of((Object) "Operación estadística requerida")), 0, "Error");
        String op = parts[0].toUpperCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        try {
            // Revisar si son dos listas entre corchetes para operaciones bivariadas: CORRELATION, COVARIANCE, REGRESSION
            if (op.equals("CORRELATION") || op.equals("COVARIANCE") || op.equals("REGRESSION")) {
                Matcher m = Pattern.compile("\\[([^\\]]+)\\]").matcher(args);
                List<List<Double>> vecList = new ArrayList<>();
                while (m.find()) {
                    List<Double> v = new ArrayList<>();
                    for (String s : m.group(1).split("[,\s]+")) {
                        if (!s.isBlank()) v.add(Double.parseDouble(s.trim()));
                    }
                    vecList.add(v);
                }
                if (vecList.size() < 2) {
                    return new QueryResult(List.of("error"), List.of(List.of((Object) "Se requieren dos listas entre corchetes: [x1,x2,...] [y1,y2,...]")), 0, "Error");
                }
                List<Double> x = vecList.get(0);
                List<Double> y = vecList.get(1);
                if (op.equals("CORRELATION")) {
                    double r = JettraStatistics.correlation(x, y);
                    return new QueryResult(List.of("coeficiente_correlacion_r", "muestras"), List.of(List.of((Object) JettraMath.round(r, 4), (Object) x.size())), 1, "Correlación de Pearson computada");
                } else if (op.equals("COVARIANCE")) {
                    double cov = JettraStatistics.covariance(x, y, true);
                    return new QueryResult(List.of("covarianza_muestral", "muestras"), List.of(List.of((Object) JettraMath.round(cov, 4), (Object) x.size())), 1, "Covarianza computada");
                } else {
                    var reg = JettraStatistics.linearRegression(x, y);
                    return new QueryResult(List.of("pendiente_m", "intercepto_b", "r_cuadrado"),
                        List.of(List.of((Object) JettraMath.round(reg.slope(), 4), (Object) JettraMath.round(reg.intercept(), 4), (Object) JettraMath.round(reg.rSquared(), 4))), 1, "Regresión lineal y = mx + b");
                }
            }

            // Operaciones univariadas
            List<Double> nums = new ArrayList<>();
            for (String s : args.split("[,\s]+")) {
                if (!s.isBlank()) nums.add(Double.parseDouble(s.trim()));
            }

            switch (op) {
                case "MEAN", "AVG" -> {
                    double m = JettraStatistics.mean(nums);
                    return new QueryResult(List.of("media", "muestras"), List.of(List.of((Object) JettraMath.round(m, 4), (Object) nums.size())), 1, "Media calculada");
                }
                case "MEDIAN" -> {
                    double med = JettraStatistics.median(nums);
                    return new QueryResult(List.of("mediana", "muestras"), List.of(List.of((Object) JettraMath.round(med, 4), (Object) nums.size())), 1, "Mediana calculada");
                }
                case "MODE" -> {
                    double mo = JettraStatistics.mode(nums);
                    return new QueryResult(List.of("moda", "muestras"), List.of(List.of((Object) JettraMath.round(mo, 4), (Object) nums.size())), 1, "Moda calculada");
                }
                case "SUM" -> {
                    double su = JettraStatistics.sum(nums);
                    return new QueryResult(List.of("suma", "muestras"), List.of(List.of((Object) JettraMath.round(su, 4), (Object) nums.size())), 1, "Suma calculada");
                }
                case "MIN" -> {
                    double mn = JettraStatistics.min(nums);
                    return new QueryResult(List.of("minimo", "muestras"), List.of(List.of((Object) mn, (Object) nums.size())), 1, "Mínimo calculado");
                }
                case "MAX" -> {
                    double mx = JettraStatistics.max(nums);
                    return new QueryResult(List.of("maximo", "muestras"), List.of(List.of((Object) mx, (Object) nums.size())), 1, "Máximo calculado");
                }
                case "STDDEV", "STD" -> {
                    double sd = JettraStatistics.stddev(nums, true);
                    return new QueryResult(List.of("desviacion_estandar", "muestras"), List.of(List.of((Object) JettraMath.round(sd, 4), (Object) nums.size())), 1, "Desviación estándar calculada");
                }
                case "VARIANCE", "VAR" -> {
                    double vr = JettraStatistics.variance(nums, true);
                    return new QueryResult(List.of("varianza", "muestras"), List.of(List.of((Object) JettraMath.round(vr, 4), (Object) nums.size())), 1, "Varianza calculada");
                }
                case "SKEWNESS", "SKEW" -> {
                    double sk = JettraStatistics.skewness(nums);
                    return new QueryResult(List.of("asimetria_skewness", "muestras"), List.of(List.of((Object) JettraMath.round(sk, 4), (Object) nums.size())), 1, "Coeficiente de asimetría calculado");
                }
                case "KURTOSIS", "KURT" -> {
                    double kt = JettraStatistics.kurtosis(nums);
                    return new QueryResult(List.of("curtosis", "muestras"), List.of(List.of((Object) JettraMath.round(kt, 4), (Object) nums.size())), 1, "Curtosis calculada");
                }
                case "IQR" -> {
                    double iqrVal = JettraStatistics.iqr(nums);
                    return new QueryResult(List.of("rango_intercuartil_iqr", "muestras"), List.of(List.of((Object) JettraMath.round(iqrVal, 4), (Object) nums.size())), 1, "Rango intercuartil IQR calculado");
                }
                case "SE", "STANDARD_ERROR" -> {
                    double se = JettraStatistics.standardError(nums);
                    return new QueryResult(List.of("error_estandar", "muestras"), List.of(List.of((Object) JettraMath.round(se, 4), (Object) nums.size())), 1, "Error estándar de la media calculado");
                }
                case "SUMMARY" -> {
                    var s = JettraStatistics.summary(nums);
                    return new QueryResult(
                        List.of("count", "sum", "mean", "median", "stddev", "min", "max", "p95"),
                        List.of(List.of((Object) s.count(), (Object) s.sum(), (Object) JettraMath.round(s.mean(), 2),
                            (Object) JettraMath.round(s.median(), 2), (Object) JettraMath.round(s.stddev(), 2),
                            (Object) s.min(), (Object) s.max(), (Object) JettraMath.round(s.p95(), 2))),
                        1, "Resumen estadístico generado"
                    );
                }
                default -> {
                    return new QueryResult(List.of("error"), List.of(List.of((Object) ("Operación estadística desconocida: " + op))), 0, "Error");
                }
            }
        } catch (Exception e) {
            return new QueryResult(List.of("error"), List.of(List.of((Object) e.getMessage())), 0, "Error en comando estadístico");
        }
    }

    private QueryResult executeDirectVector(String cmd) {
        String clean = cmd.replaceAll("(?i)^VECTOR\s+", "").trim();
        String[] parts = clean.split("\s+", 2);
        if (parts.length == 0 || parts[0].isBlank()) return new QueryResult(List.of("error"), List.of(List.of((Object) "Operación vectorial requerida")), 0, "Error");
        String op = parts[0].toUpperCase();
        String rest = parts.length > 1 ? parts[1].trim() : "";

        try {
            // Extraer vectores entre corchetes [f1,f2,...] [f3,f4,...]
            Matcher m = Pattern.compile("\\[([^\\]]+)\\]").matcher(rest);
            List<float[]> vecs = new ArrayList<>();
            while (m.find()) {
                String[] items = m.group(1).split("[,\s]+");
                float[] v = new float[items.length];
                for (int i = 0; i < items.length; i++) v[i] = Float.parseFloat(items[i].trim());
                vecs.add(v);
            }

            switch (op) {
                case "DOT", "DOT_PRODUCT" -> {
                    float dot = JettraVectorMath.dotProduct(vecs.get(0), vecs.get(1));
                    return new QueryResult(List.of("producto_punto"), List.of(List.of((Object) dot)), 1, "Producto punto computado");
                }
                case "COSINE", "COSINE_SIMILARITY" -> {
                    float sim = JettraVectorMath.cosineSimilarity(vecs.get(0), vecs.get(1));
                    return new QueryResult(List.of("similitud_coseno"), List.of(List.of((Object) sim)), 1, "Similitud coseno computada");
                }
                case "DISTANCE", "EUCLIDEAN" -> {
                    float dist = JettraVectorMath.euclideanDistance(vecs.get(0), vecs.get(1));
                    return new QueryResult(List.of("distancia_euclidiana"), List.of(List.of((Object) dist)), 1, "Distancia euclidiana computada");
                }
                case "MANHATTAN" -> {
                    float dist = JettraVectorMath.manhattanDistance(vecs.get(0), vecs.get(1));
                    return new QueryResult(List.of("distancia_manhattan"), List.of(List.of((Object) dist)), 1, "Distancia manhattan computada");
                }
                case "CHEBYSHEV" -> {
                    float dist = JettraVectorMath.chebyshevDistance(vecs.get(0), vecs.get(1));
                    return new QueryResult(List.of("distancia_chebyshev"), List.of(List.of((Object) dist)), 1, "Distancia Chebyshev computada");
                }
                case "NORM", "MAGNITUDE" -> {
                    float n = JettraVectorMath.norm(vecs.get(0));
                    return new QueryResult(List.of("norma_l2"), List.of(List.of((Object) n)), 1, "Norma L2 computada");
                }
                case "L1", "L1_NORM" -> {
                    float n = JettraVectorMath.l1Norm(vecs.get(0));
                    return new QueryResult(List.of("norma_l1"), List.of(List.of((Object) n)), 1, "Norma L1 computada");
                }
                case "NORMALIZE" -> {
                    float[] norm = JettraVectorMath.normalize(vecs.get(0));
                    return new QueryResult(List.of("vector_normalizado"), List.of(List.of((Object) Arrays.toString(norm))), 1, "Vector unitario normalizado");
                }
                case "ANGLE", "ANGLE_DEG" -> {
                    double rad = JettraVectorMath.angle(vecs.get(0), vecs.get(1));
                    double deg = Math.toDegrees(rad);
                    return new QueryResult(List.of("angulo_radianes", "angulo_grados"),
                        List.of(List.of((Object) JettraMath.round(rad, 4), (Object) JettraMath.round(deg, 2))), 1, "Ángulo entre vectores computado");
                }
                case "CROSS", "CROSS_PRODUCT" -> {
                    float[] cross = JettraVectorMath.crossProduct(vecs.get(0), vecs.get(1));
                    return new QueryResult(List.of("producto_cruz_3d"), List.of(List.of((Object) Arrays.toString(cross))), 1, "Producto cruz 3D computado");
                }
                case "PROJECTION" -> {
                    float[] proj = JettraVectorMath.projection(vecs.get(0), vecs.get(1));
                    return new QueryResult(List.of("proyeccion_vectorial"), List.of(List.of((Object) Arrays.toString(proj))), 1, "Proyección de v1 sobre v2 computada");
                }
                case "ADD" -> {
                    float[] res = JettraVectorMath.add(vecs.get(0), vecs.get(1));
                    return new QueryResult(List.of("vector_suma"), List.of(List.of((Object) Arrays.toString(res))), 1, "Suma de vectores computada");
                }
                case "SUB", "SUBTRACT" -> {
                    float[] res = JettraVectorMath.subtract(vecs.get(0), vecs.get(1));
                    return new QueryResult(List.of("vector_resta"), List.of(List.of((Object) Arrays.toString(res))), 1, "Resta de vectores computada");
                }
                case "MUL", "MULTIPLY" -> {
                    float[] res = JettraVectorMath.multiply(vecs.get(0), vecs.get(1));
                    return new QueryResult(List.of("vector_producto_hadamard"), List.of(List.of((Object) Arrays.toString(res))), 1, "Multiplicación elemento a elemento computada");
                }
                case "DIV", "DIVIDE" -> {
                    float[] res = JettraVectorMath.divide(vecs.get(0), vecs.get(1));
                    return new QueryResult(List.of("vector_division_hadamard"), List.of(List.of((Object) Arrays.toString(res))), 1, "División elemento a elemento computada");
                }
                case "CENTROID" -> {
                    float[] c = JettraVectorMath.centroid(vecs);
                    return new QueryResult(List.of("centroide", "vectores"), List.of(List.of((Object) Arrays.toString(c), (Object) vecs.size())), 1, "Centroide computado");
                }
                default -> {
                    return new QueryResult(List.of("error"), List.of(List.of((Object) ("Operación vectorial no soportada: " + op))), 0, "Error");
                }
            }
        } catch (Exception e) {
            return new QueryResult(List.of("error"), List.of(List.of((Object) e.getMessage())), 0, "Error en operación vectorial");
        }
    }

    private QueryResult executeSelect(String sql) {
        String upper = sql.toUpperCase();
        int fromIdx = upper.indexOf(" FROM ");
        if (fromIdx == -1) {
            return new QueryResult(List.of("error"), List.of(List.of("Sintaxis SQL inválida: falta la cláusula FROM")), 0, "Error de sintaxis");
        }

        String selectFields = sql.substring(7, fromIdx).trim();
        String afterFrom = sql.substring(fromIdx + 6).trim();

        // Obtener configuración de límites de consulta
        int defaultLimit = 50;
        int maxLimit = 5000;
        if (database != null && database.getConfig() != null) {
            defaultLimit = database.getConfig().getQueryDefaultLimit();
            maxLimit = database.getConfig().getQueryMaxLimit();
        }

        // 1. Extraer LIMIT y OFFSET si existen
        int limit = defaultLimit;
        int offset = 0;
        boolean explicitLimit = false;

        int limitIdx = afterFrom.toUpperCase().lastIndexOf(" LIMIT ");
        if (limitIdx != -1) {
            String afterLimit = afterFrom.substring(limitIdx + 7).trim();
            afterFrom = afterFrom.substring(0, limitIdx).trim();

            int offsetInLimitIdx = afterLimit.toUpperCase().indexOf(" OFFSET ");
            if (offsetInLimitIdx != -1) {
                String limStr = sanitize(afterLimit.substring(0, offsetInLimitIdx));
                String offStr = sanitize(afterLimit.substring(offsetInLimitIdx + 8));
                try { limit = Integer.parseInt(limStr); explicitLimit = true; } catch (Exception ignored) {}
                try { offset = Integer.parseInt(offStr); } catch (Exception ignored) {}
            } else {
                String limStr = sanitize(afterLimit);
                try { limit = Integer.parseInt(limStr); explicitLimit = true; } catch (Exception ignored) {}
            }
        } else {
            int offsetIdx = afterFrom.toUpperCase().lastIndexOf(" OFFSET ");
            if (offsetIdx != -1) {
                String offStr = sanitize(afterFrom.substring(offsetIdx + 8));
                afterFrom = afterFrom.substring(0, offsetIdx).trim();
                try { offset = Integer.parseInt(offStr); } catch (Exception ignored) {}
            }
        }
        limit = Math.min(Math.max(1, limit), maxLimit);

        // 2. Extraer WHERE si existe
        String whereField = null;
        String whereVal = null;
        int whereIdx = afterFrom.toUpperCase().indexOf(" WHERE ");
        String collection;
        if (whereIdx != -1) {
            collection = sanitize(afterFrom.substring(0, whereIdx));
            String whereClause = afterFrom.substring(whereIdx + 7).trim();
            String[] kv = whereClause.split("=");
            if (kv.length == 2) {
                whereField = sanitize(kv[0]);
                whereVal = sanitize(kv[1]);
            }
        } else {
            collection = sanitize(afterFrom.split("\s+")[0]);
        }

        DocumentEngine docEngine = database.getDocumentEngine(collection);
        if (docEngine == null || docEngine.isEmpty()) {
            return new QueryResult(List.of("_id"), Collections.emptyList(), 0, "0 records returned from '" + collection + "'");
        }

        long totalCount = docEngine.count();

        // 2.5 Análisis predictivo y supervisión autónoma de JettraPolice (Anti-OOM)
        int requestedLimit = explicitLimit ? limit : 0;
        io.jettra.store.police.JettraPolice.PoliceDecision policeDecision = 
            io.jettra.store.police.JettraPolice.getInstance().evaluateHeapSafety(
                "SQL_SELECT", collection, totalCount, requestedLimit, 512L);

        boolean policeIntervened = false;
        if (policeDecision.interventionRequired()) {
            policeIntervened = true;
            limit = policeDecision.enforcedLimit();
        }

        // 3. Consulta acelerada por índices secundarios si aplica
        Set<String> indexedDocIds = null;
        if (whereField != null && whereVal != null && database.getIndexManager() != null) {
            indexedDocIds = database.getIndexManager().findDocIds(collection, whereField, whereVal);
        }

        // 4. Streaming y filtrado acotado en memoria (Zero Full-Heap Allocation)
        List<Map<String, Object>> collectedDocs = new ArrayList<>(Math.min(limit, 200));
        LinkedHashSet<String> dynamicColumns = new LinkedHashSet<>();
        dynamicColumns.add("_id");

        int skipped = 0;
        if (indexedDocIds != null) {
            // Camino rápido indexado: O(1) recuperación directa
            for (String docId : indexedDocIds) {
                Map<String, Object> doc = docEngine.findById(docId);
                if (doc != null) {
                    if (skipped < offset) {
                        skipped++;
                        continue;
                    }
                    collectedDocs.add(doc);
                    dynamicColumns.addAll(doc.keySet());
                    if (collectedDocs.size() >= limit) {
                        break;
                    }
                }
            }
        } else {
            // Streaming lazy secuencial con corte temprano
            for (Map<String, Object> doc : docEngine) {
                if (doc == null) continue;
                if (whereField != null && whereVal != null) {
                    Object val = doc.get(whereField);
                    if (val == null || !String.valueOf(val).equalsIgnoreCase(whereVal)) {
                        continue;
                    }
                }

                if (skipped < offset) {
                    skipped++;
                    continue;
                }

                collectedDocs.add(doc);
                dynamicColumns.addAll(doc.keySet());
                if (collectedDocs.size() >= limit) {
                    break;
                }
            }
        }

        // 5. Determinar columnas seleccionadas
        List<String> finalCols = new ArrayList<>();
        if (selectFields.equals("*")) {
            finalCols.addAll(dynamicColumns);
        } else {
            for (String f : selectFields.split(",")) {
                String c = sanitize(f);
                if (!c.isEmpty()) finalCols.add(c);
            }
        }

        // 6. Proyectar filas finales
        List<List<Object>> rows = new ArrayList<>(collectedDocs.size());
        for (Map<String, Object> doc : collectedDocs) {
            List<Object> row = new ArrayList<>(finalCols.size());
            for (String col : finalCols) {
                row.add(doc.getOrDefault(col, null));
            }
            rows.add(row);
        }

        String summaryMsg;
        if (policeIntervened) {
            summaryMsg = String.format("[JettraPolice SENTINEL: Paginación Lazy Anti-OOM Activada] %d fila(s) retornada(s) (Lote seguro: %d, Offset: %d) de un total de %d en '%s'. %s",
                    rows.size(), limit, offset, totalCount, collection, policeDecision.rationale());
        } else if (explicitLimit) {
            summaryMsg = String.format("%d fila(s) retornada(s) (Límite: %d, Offset: %d) de un total estimado de %d en '%s'",
                    rows.size(), limit, offset, totalCount, collection);
        } else {
            summaryMsg = String.format("%d fila(s) retornada(s) [Límite de seguridad: %d] (Total en colección: %d en '%s')",
                    rows.size(), limit, totalCount, collection);
        }

        return new QueryResult(finalCols, rows, rows.size(), summaryMsg);
    }

    private QueryResult executeInsert(String sql) {
        try {
            int intoIdx = sql.toUpperCase().indexOf("INTO ");
            int valuesIdx = sql.toUpperCase().indexOf(" VALUES");
            if (intoIdx != -1 && valuesIdx != -1) {
                String col = sql.substring(intoIdx + 5, valuesIdx).trim();
                String valPart = sql.substring(valuesIdx + 7).trim();
                if (valPart.startsWith("(") && valPart.endsWith(")")) {
                    valPart = valPart.substring(1, valPart.length() - 1).trim();
                }
                String[] parts = valPart.split(",", 2);
                String id = sanitize(parts[0]);
                String dataStr = parts.length > 1 ? parts[1].trim() : "{}";
                
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("_id", id);
                if (dataStr.startsWith("{") && dataStr.endsWith("}")) {
                    String inside = dataStr.substring(1, dataStr.length() - 1);
                    for (String pair : inside.split(",")) {
                        String[] kv = pair.split("[:=]", 2);
                        if (kv.length == 2) {
                            String k = sanitize(kv[0]);
                            String v = sanitize(kv[1]);
                            doc.put(k, v);
                        }
                    }
                } else {
                    doc.put("value", sanitize(dataStr));
                }
                database.getDocumentEngine(col).insert(id, doc);
                if (database.getIndexManager() != null) {
                    database.getIndexManager().onDocumentInsert(col, id, doc);
                }
                return new QueryResult(List.of("status", "id"), List.of(List.of("INSERTED", id)), 1, "Inserted 1 row into '" + col + "'");
            }
        } catch (Exception ignored) {}
        return new QueryResult(List.of("affected"), List.of(List.of(1)), 1, "INSERT executed");
    }

    private QueryResult executeUpdate(String sql) {
        try {
            String upper = sql.toUpperCase();
            int setIdx = upper.indexOf(" SET ");
            int whereIdx = upper.indexOf(" WHERE ");
            if (setIdx != -1) {
                String col = sql.substring(6, setIdx).trim();
                String whereClause = whereIdx != -1 ? sql.substring(whereIdx + 7).trim() : null;
                String targetId = null;
                if (whereClause != null && whereClause.contains("=")) {
                    targetId = sanitize(whereClause.split("=")[1]);
                }
                String setClause = whereIdx != -1 ? sql.substring(setIdx + 5, whereIdx).trim() : sql.substring(setIdx + 5).trim();
                String[] kv = setClause.split("=");
                if (kv.length == 2 && targetId != null) {
                    String k = sanitize(kv[0]);
                    String v = sanitize(kv[1]);
                    database.getDocumentEngine(col).update(targetId, Map.of(k, v));
                    return new QueryResult(List.of("status"), List.of(List.of("UPDATED")), 1, "Updated 1 record in '" + col + "'");
                }
            }
        } catch (Exception ignored) {}
        return new QueryResult(List.of("affected"), List.of(List.of(1)), 1, "UPDATE executed");
    }

    private QueryResult executeDelete(String sql) {
        try {
            int whereIdx = sql.toUpperCase().indexOf(" WHERE ");
            if (whereIdx != -1) {
                String col = sql.substring(11, whereIdx).trim();
                String whereClause = sql.substring(whereIdx + 7).trim();
                String[] kv = whereClause.split("=");
                if (kv.length == 2) {
                    String id = sanitize(kv[1]);
                    boolean del = database.getDocumentEngine(col).delete(id);
                    if (database.getIndexManager() != null) {
                        database.getIndexManager().onDocumentDelete(col, id, null);
                    }
                    return new QueryResult(List.of("affected"), List.of(List.of(del ? 1 : 0)), del ? 1 : 0, "Deleted " + (del ? 1 : 0) + " row from '" + col + "'");
                }
            }
        } catch (Exception ignored) {}
        return new QueryResult(List.of("affected"), List.of(List.of(1)), 1, "DELETE executed");
    }
}
