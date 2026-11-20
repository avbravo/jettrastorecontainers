package io.jettra.driver.query;

import io.jettra.store.engine.query.JettraSQLProcessor.QueryResult;

import java.util.*;
import java.util.stream.Stream;

/**
 * Representa el conjunto de resultados retornado por la ejecución de una consulta JettraSQL,
 * proporcionando navegación secuencial basada en cursor, tipado seguro y mapeo O(1).
 */
public final class JettraResultSet implements Iterable<Map<String, Object>> {
    private final List<String> columns;
    private final List<List<Object>> rows;
    private final int affectedRows;
    private final String message;
    private final Map<String, Integer> columnIndices;
    private int cursor = -1;

    public JettraResultSet(QueryResult queryResult) {
        if (queryResult != null) {
            this.columns = queryResult.columns() != null ? List.copyOf(queryResult.columns()) : List.of();
            this.rows = queryResult.rows() != null ? List.copyOf(queryResult.rows()) : List.of();
            this.affectedRows = queryResult.affectedRows();
            this.message = queryResult.message() != null ? queryResult.message() : "";
        } else {
            this.columns = List.of();
            this.rows = List.of();
            this.affectedRows = 0;
            this.message = "";
        }

        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < this.columns.size(); i++) {
            map.put(this.columns.get(i).toLowerCase(Locale.ROOT), i);
        }
        this.columnIndices = Collections.unmodifiableMap(map);
    }

    public JettraResultSet(List<String> columns, List<List<Object>> rows, int affectedRows, String message) {
        this(new QueryResult(columns, rows, affectedRows, message));
    }

    public boolean next() {
        if (cursor + 1 < rows.size()) {
            cursor++;
            return true;
        }
        return false;
    }

    public boolean previous() {
        if (cursor > 0) {
            cursor--;
            return true;
        }
        return false;
    }

    public boolean first() {
        if (!rows.isEmpty()) {
            cursor = 0;
            return true;
        }
        return false;
    }

    public boolean last() {
        if (!rows.isEmpty()) {
            cursor = rows.size() - 1;
            return true;
        }
        return false;
    }

    public void reset() {
        cursor = -1;
    }

    public int getCursor() {
        return cursor;
    }

    public int size() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<List<Object>> getRows() {
        return rows;
    }

    public int getAffectedRows() {
        return affectedRows;
    }

    public String getMessage() {
        return message;
    }

    public String summary() {
        return message;
    }

    private void checkCursor() {
        if (cursor < 0 || cursor >= rows.size()) {
            throw new IllegalStateException("El cursor de JettraResultSet no está posicionado en una fila válida (cursor: " + cursor + ", total filas: " + rows.size() + "). Llame a next() primero.");
        }
    }

    private int resolveColumnIndex(String columnName) {
        if (columnName == null) throw new IllegalArgumentException("El nombre de columna no puede ser nulo");
        Integer idx = columnIndices.get(columnName.trim().toLowerCase(Locale.ROOT));
        if (idx == null) {
            throw new NoSuchElementException("Columna '" + columnName + "' no encontrada en el resultado. Columnas disponibles: " + columns);
        }
        return idx;
    }

    public Object getObject(int columnIndex) {
        checkCursor();
        List<Object> row = rows.get(cursor);
        if (columnIndex < 0 || columnIndex >= row.size()) {
            throw new IndexOutOfBoundsException("Índice de columna " + columnIndex + " fuera de rango [0, " + (row.size() - 1) + "]");
        }
        return row.get(columnIndex);
    }

    public Object getObject(String columnName) {
        return getObject(resolveColumnIndex(columnName));
    }

    public String getString(int columnIndex) {
        Object val = getObject(columnIndex);
        return val != null ? val.toString() : null;
    }

    public String getString(String columnName) {
        return getString(resolveColumnIndex(columnName));
    }

    public long getLong(int columnIndex) {
        Object val = getObject(columnIndex);
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString().trim());
    }

    public long getLong(String columnName) {
        return getLong(resolveColumnIndex(columnName));
    }

    public double getDouble(int columnIndex) {
        Object val = getObject(columnIndex);
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        return Double.parseDouble(val.toString().trim());
    }

    public double getDouble(String columnName) {
        return getDouble(resolveColumnIndex(columnName));
    }

    public int getInt(int columnIndex) {
        Object val = getObject(columnIndex);
        if (val == null) return 0;
        if (val instanceof Number n) return n.intValue();
        return Integer.parseInt(val.toString().trim());
    }

    public int getInt(String columnName) {
        return getInt(resolveColumnIndex(columnName));
    }

    public boolean getBoolean(int columnIndex) {
        Object val = getObject(columnIndex);
        if (val == null) return false;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString().trim());
    }

    public boolean getBoolean(String columnName) {
        return getBoolean(resolveColumnIndex(columnName));
    }

    public Map<String, Object> getCurrentRowAsMap() {
        checkCursor();
        List<Object> row = rows.get(cursor);
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            Object val = i < row.size() ? row.get(i) : null;
            map.put(columns.get(i), val);
        }
        return map;
    }

    public List<Map<String, Object>> asMaps() {
        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                Object val = i < row.size() ? row.get(i) : null;
                map.put(columns.get(i), val);
            }
            list.add(map);
        }
        return list;
    }

    public Stream<Map<String, Object>> stream() {
        return asMaps().stream();
    }

    @Override
    public Iterator<Map<String, Object>> iterator() {
        return asMaps().iterator();
    }

    @Override
    public String toString() {
        return "JettraResultSet[rows=" + rows.size() + ", columns=" + columns + ", message='" + message + "']";
    }
}
