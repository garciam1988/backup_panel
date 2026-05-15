package app.coincidir.api.web.admin.backups;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runner simple para scripts SQL (MySQL). Soporta comentarios '--', '#' y '/* ... *&#47;'.
 * Separa sentencias por ';' respetando strings y backticks.
 */
public class SqlScriptRunner {

    private final Connection con;

    public SqlScriptRunner(Connection con) {
        this.con = con;
    }

    public void run(String sql) throws SQLException {
        if (sql == null) return;
        for (String stmt : splitStatements(sql)) {
            String s = stmt.trim();
            if (s.isEmpty()) continue;
            try (Statement st = con.createStatement()) {
                st.execute(s);
            }
        }
    }

    private static java.util.List<String> splitStatements(String sql) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();

        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char n = (i + 1 < sql.length()) ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && n == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }

            if (!inSingle && !inDouble && !inBacktick) {
                if (c == '-' && n == '-') {
                    inLineComment = true;
                    i++;
                    continue;
                }
                if (c == '#') {
                    inLineComment = true;
                    continue;
                }
                if (c == '/' && n == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }
            }

            if (c == '\\') {
                // escape next char in strings
                cur.append(c);
                if (i + 1 < sql.length()) {
                    cur.append(sql.charAt(i + 1));
                    i++;
                }
                continue;
            }

            if (c == '\'' && !inDouble && !inBacktick) {
                inSingle = !inSingle;
                cur.append(c);
                continue;
            }
            if (c == '"' && !inSingle && !inBacktick) {
                inDouble = !inDouble;
                cur.append(c);
                continue;
            }
            if (c == '`' && !inSingle && !inDouble) {
                inBacktick = !inBacktick;
                cur.append(c);
                continue;
            }

            if (c == ';' && !inSingle && !inDouble && !inBacktick) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }

            cur.append(c);
        }

        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
