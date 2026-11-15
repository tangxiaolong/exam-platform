package com.exam.platform.modules.question.imports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 把导入文件解析成「行 × 单元格」的二维数组。
 *
 * <p>只支持 csv 与 xlsx：文档的批量导入导出都走这两种格式，
 * 其余格式由上传白名单直接挡掉，无需在此重复判断。</p>
 */
final class SheetParser {

    private SheetParser() {
    }

    static List<String[]> parseCsv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        List<String[]> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    // 连续两个引号是转义
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '"' -> quoted = true;
                case ',' -> {
                    current.add(cell.toString());
                    cell.setLength(0);
                }
                case '\r' -> {
                    // 交由 \n 处理，兼容 CRLF
                }
                case '\n' -> {
                    current.add(cell.toString());
                    cell.setLength(0);
                    rows.add(current.toArray(new String[0]));
                    current = new ArrayList<>();
                }
                default -> cell.append(ch);
            }
        }
        if (cell.length() > 0 || !current.isEmpty()) {
            current.add(cell.toString());
            rows.add(current.toArray(new String[0]));
        }
        return rows;
    }

    static List<String[]> parseXlsx(byte[] content) throws IOException {
        DataFormatter formatter = new DataFormatter();
        List<String[]> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                return rows;
            }
            int lastColumn = 0;
            for (Row row : sheet) {
                lastColumn = Math.max(lastColumn, row.getLastCellNum());
            }
            for (Row row : sheet) {
                if (row == null) {
                    rows.add(new String[0]);
                    continue;
                }
                String[] values = new String[Math.max(lastColumn, 1)];
                for (int c = 0; c < values.length; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    values[c] = cell == null ? "" : formatter.formatCellValue(cell);
                }
                rows.add(values);
            }
        }
        return rows;
    }
}
