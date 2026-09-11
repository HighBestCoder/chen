import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;

import java.util.ArrayList;
import java.util.List;

/** Exercises actual result-to-WebGUI mapping, including alias collisions. */
public class TestDataViewColumns {
    public static void main(String[] args) throws Exception {
        DataView view = new DataView("test", null, null);
        List<Field> fields = new ArrayList<>();
        for (String name : List.of("x", "x", "x", "x(1)")) {
            Field field = new Field();
            field.setName(name);
            field.setNullable(true);
            field.setPrimaryKey(true);
            fields.add(field);
        }
        view.setLoadDataInterface((params, sink) -> {
            SQLQueryResult result = new SQLQueryResult("SELECT aliases");
            result.setFields(fields);
            result.setData(List.of(List.of(1, 2, 3, 4)));
            result.setHasResultSet(true);
            return result;
        });
        view.loadData();
        var row = view.getData().getData().get(0);
        if (view.getData().getFields().stream().anyMatch(f -> !f.isNullable() || !f.isPrimaryKey())) {
            throw new AssertionError("Display column copy lost constraints");
        }
        if (row.size() != 4 || !row.values().containsAll(List.of(1, 2, 3, 4))) {
            throw new AssertionError("Duplicate aliases lost data: " + row);
        }
        if (!fields.stream().map(Field::getName).toList().equals(List.of("x", "x", "x", "x(1)"))) {
            throw new AssertionError("WebGUI mapping mutated audit/driver metadata");
        }
        view.loadData();
        if (view.getData().getData().get(0).size() != 4) {
            throw new AssertionError("Refresh lost duplicate columns");
        }
        view.setLoadDataInterface((params, sink) -> {
            SQLQueryResult unavailable = new SQLQueryResult("unsupported value");
            unavailable.setSizeStatsStatus("unavailable");
            unavailable.setStreamedSizeBytes(0);
            return unavailable;
        });
        view.loadData();
        if (view.getState().getSizeBytes() != -1) {
            throw new AssertionError("Unavailable measurement displayed as zero bytes");
        }
        System.out.println("OK: duplicate aliases retain every value and original metadata");
    }
}
