package org.jumpserver.chen.framework.console.dataview;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.console.action.DataViewAction;
import org.jumpserver.chen.framework.console.component.Logger;
import org.jumpserver.chen.framework.console.entity.response.SQLResult;
import org.jumpserver.chen.framework.console.state.DataViewState;
import org.jumpserver.chen.framework.console.state.StateManager;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.RowConsumer;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.ws.io.PacketIO;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.BeanUtils;

@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class DataView extends SQLResult {
    private final String title;
    private final StateManager<DataViewState> stateManager;
    private LoadDataInterface loadDataInterface;

    private DataViewData data = new DataViewData();
    private int updateCount;
    private boolean hasTable = true;
    private DataViewState state;

    private Logger consoleLogger;

    public DataView(String title, PacketIO packetIO, Logger logger) {
        this.title = title;
        this.state = new DataViewState(title);
        this.stateManager = new StateManager<>(this.state, packetIO);
        this.consoleLogger = logger;
    }


    public void doAction(DataViewAction action) throws SQLException {
        switch (action.getAction()) {
            case DataViewAction.ACTION_FIRST_PAGE -> {
                this.firstPage();
            }
            case DataViewAction.ACTION_PREV_PAGE -> {
                this.prevPage();
            }
            case DataViewAction.ACTION_NEXT_PAGE -> {
                this.nextPage();
            }
            case DataViewAction.ACTION_LAST_PAGE -> {
                this.lastPage();
            }
            case DataViewAction.ACTION_REFRESH -> {
                this.refresh();
            }
            case DataViewAction.ACTION_TOGGLE_PINNED -> {
                this.getStateManager().getState().setPinned(!this.getStateManager().getState().isPinned());
            }
            case DataViewAction.ACTION_CHANGE_LIMIT -> {
                this.changeLimit((int) action.getData());
            }
            case DataViewAction.ACTION_EXPORT -> {
                this.export(action.getData());
            }
        }
    }

    public void loadData() throws SQLException {
        SQLQueryParams queryParams = new SQLQueryParams();
        queryParams.setLimit(this.state.getLimit());
        queryParams.setOffset((this.state.getPage() - 1) * this.state.getLimit());

        var result = this.loadDataInterface
                .loadData(queryParams, null);

        this.fullData(result);
    }

    private void fullData(SQLQueryResult result) {
        if (!result.isHasResultSet()) {
            this.hasTable = false;
            this.updateCount = result.getUpdateCount();
            return;
        }

        this.state.setPaged(result.isPaged());
        this.state.setManualLimitDetected(result.isManualLimitDetected());

        this.data.getFields().clear();
        this.data.getData().clear();

        this.getStateManager().getState().setTotal(result.getTotal());
        this.getStateManager().getState().setSizeBytes(
                "unavailable".equals(result.getSizeStatsStatus()) ? -1 : result.getStreamedSizeBytes());


        // Display keys must be unique, including aliases that already contain
        // a suffix. Keep JDBC/audit metadata intact when generating UI names.
        Set<String> reserved = new HashSet<>();
        result.getFields().forEach(field -> reserved.add(field.getName()));
        Set<String> used = new HashSet<>();
        List<Field> displayFields = new ArrayList<>();
        for (Field original : result.getFields()) {
            Field field = new Field();
            BeanUtils.copyProperties(original, field);
            String name = original.getName();
            if (used.contains(name)) {
                int suffix = 1;
                do {
                    name = original.getName() + "(" + suffix++ + ")";
                } while (reserved.contains(name) || used.contains(name));
            }
            used.add(name);
            field.setName(name);
            displayFields.add(field);
        }
        this.data.setFields(displayFields);


        for (List<Object> row : result.getData()) {
            Map<String, Object> map = new HashMap<>();
            for (int i = 0; i < row.size(); i++) {
                map.put(this.data.getFields().get(i).getName(), row.get(i));
            }
            this.data.getData().add(map);
        }
    }

    private static void writeString(BufferedWriter writer, Object object) throws IOException {
        var str = object.toString();

        if (str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r")) {
            str = "\"" + str.replace("\"", "\"\"") + "\"";
        }
        writer.write(str);
    }

    private static void writeRow(BufferedWriter writer, List<Field> fields, List<Object> row) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                writer.write(",");
            }
            Object obj = i < row.size() ? row.get(i) : null;
            if (obj == null) {
                writer.write("NULL");
            } else if (obj instanceof Date) {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                writeString(writer, fmt.format(obj));
            } else {
                writeString(writer, obj);
            }
        }
        writer.newLine();
    }

    public void export(Object request) throws SQLException {
        var session = SessionManager.getCurrentSession();
        ExportRequest exportRequest = ExportRequest.from(request);
        String scope = exportRequest.scope();
        CommandRecord command = new CommandRecord(String.format("Export data: %s", this.title));
        if (!session.canDownload()) {
            command.setError("Export denied: no download permission for this asset");
            session.recordCommand(command);
            this.consoleLogger.warn("Export denied: no download permission for this asset");
            return;
        }
        if (!List.of("current", "selected", "all").contains(scope)) {
            throw new SQLException("Unknown export scope: " + scope);
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
        String timestamp = LocalDateTime.now().format(formatter);
        var f = session.createFile(String.format("data_%s_%d.csv", timestamp, System.nanoTime()));

        try (BufferedWriter writer = Files.newBufferedWriter(f.toPath())) {
            // UTF-8 BOM so Excel opens non-ASCII (e.g. CJK) CSV without mojibake.
            writer.write('\uFEFF');

            if (scope.equals("current")) {
                writeMappedRows(writer, this.data.getFields(), this.data.getData());
                command.setOutput(String.format("%d rows exported", this.data.getData().size()));
            }

            if (scope.equals("selected")) {
                List<Map<String, Object>> selectedRows = exportRequest.rows();
                writeMappedRows(writer, this.data.getFields(), selectedRows);
                command.setOutput(String.format("%d rows exported", selectedRows.size()));
            }

            if (scope.equals("all")) {
                SQLQueryParams queryParams = new SQLQueryParams();
                queryParams.setLimit(-1);

                final BufferedWriter w = writer;
                final long[] written = {0};

                this.loadDataInterface.loadData(queryParams, new RowConsumer() {
                    private List<Field> fields;

                    @Override
                    public void begin(List<Field> fs) throws SQLException {
                        this.fields = fs;
                        try {
                            writeMappedRows(w, fs, List.of());
                        } catch (IOException e) {
                            throw new SQLException(e);
                        }
                    }

                    @Override
                    public void accept(List<Object> row) throws SQLException {
                        try {
                            writeRow(w, this.fields, row);
                        } catch (IOException e) {
                            throw new SQLException(e);
                        }
                        written[0]++;
                    }

                    @Override
                    public void finish() throws SQLException {
                        try { w.flush(); } catch (IOException e) { throw new SQLException(e); }
                    }
                });

                command.setOutput(String.format("%d rows exported", written[0]));
            }
            writer.flush();

        } catch (IOException | SQLException | RuntimeException e) {
            command.setError(e.getMessage());
            session.recordCommand(command);
            try {
                Files.deleteIfExists(f.toPath());
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            if (e instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("Export failed: " + e.getMessage(), e);
        }
        log.info("Export finished: user={} view={} scope={} file={} — {}",
                session.getUsername(), this.title, scope, f.getName(), command.getOutput());
        this.consoleLogger.success(command.getOutput());
        session.recordCommand(command);
        session.getController().sendFile(f.getName());
    }

    private static void writeMappedRows(BufferedWriter writer, List<Field> fields, List<Map<String, Object>> rows)
            throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                writer.write(",");
            }
            writeString(writer, fields.get(i).getName());
        }
        writer.newLine();

        for (Map<String, Object> row : rows) {
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    writer.write(",");
                }
                Field field = fields.get(i);
                Object obj = row.get(field.getName());
                if (obj == null) {
                    writer.write("NULL");
                } else if (obj instanceof Date) {
                    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    writeString(writer, fmt.format(obj));
                } else {
                    writeString(writer, obj);
                }
            }
            writer.newLine();
        }
    }

    private record ExportRequest(String scope, List<Map<String, Object>> rows) {
        static ExportRequest from(Object request) {
            if (request instanceof Map<?, ?> map) {
                Object scope = map.get("scope");
                Object rows = map.get("rows");
                return new ExportRequest(
                        scope == null ? "current" : scope.toString(),
                        parseRows(rows));
            }
            return new ExportRequest(request == null ? "current" : request.toString(), List.of());
        }

        private static List<Map<String, Object>> parseRows(Object value) {
            if (!(value instanceof List<?> rawRows)) {
                return List.of();
            }
            List<Map<String, Object>> parsed = new ArrayList<>();
            for (Object rawRow : rawRows) {
                if (!(rawRow instanceof Map<?, ?> rawMap)) {
                    continue;
                }
                Map<String, Object> row = new HashMap<>();
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    if (entry.getKey() != null) {
                        row.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                parsed.add(row);
            }
            return parsed;
        }
    }


    public void firstPage() throws SQLException {
        var p = this.getStateManager().getState().getPage();
        try {
            this.getStateManager().getState().setPage(1);
            this.loadData();
        } catch (SQLException e) {
            this.getStateManager().getState().setPage(p);
            throw e;
        }
    }

    public void prevPage() throws SQLException {
        var p = this.getStateManager().getState().getPage();
        try {
            this.getStateManager().getState().setPage(this.getStateManager().getState().getPage() - 1);
            this.loadData();
        } catch (SQLException e) {
            this.getStateManager().getState().setPage(p);
            throw e;
        }
    }

    public void nextPage() throws SQLException {
        var p = this.getStateManager().getState().getPage();
        try {
            this.getStateManager().getState().setPage(this.getStateManager().getState().getPage() + 1);
            this.loadData();
        } catch (SQLException e) {
            this.getStateManager().getState().setPage(p);
            throw e;
        }
    }


    public void lastPage() throws SQLException {
        var p = this.getStateManager().getState().getPage();
        try {
            if (this.state.getTotal() > 0) {
                var page = this.getState().getTotal() % this.getState().getLimit() > 0 ?
                        this.getState().getTotal() / this.getState().getLimit() + 1 : this.getState().getTotal() / this.getState().getLimit();
                this.getStateManager().getState().setPage(page);
            }
            this.loadData();
        } catch (SQLException e) {
            this.getStateManager().getState().setPage(p);
            throw e;
        }
    }

    public void changeLimit(int limit) throws SQLException {
        var oldLimit = this.getStateManager().getState().getLimit();
        var oldPage = this.getStateManager().getState().getPage();
        try {
            this.getStateManager().getState().setPage(1);
            this.getStateManager().getState().setLimit(limit);
            this.loadData();
        } catch (SQLException e) {
            this.getStateManager().getState().setLimit(oldLimit);
            this.getStateManager().getState().setPage(oldPage);
            throw e;
        }
    }

    public void refresh() throws SQLException {
        this.loadData();
    }


    public void sortBy(String field) {
    }
}
