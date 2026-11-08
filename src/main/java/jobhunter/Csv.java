package jobhunter;

import java.util.*;

/** Turns your applications into a CSV that Excel opens correctly. Pure functions, no I/O. */
final class Csv {
    private Csv() {}

    static final String HEADER = "Date,Company,Title,Source,Status,Link";

    /** Applied and Approved jobs, newest first. UTF-8 BOM so Excel shows accents; CRLF line ends. */
    static String applications(List<Job> jobs) {
        List<Job> rows = new ArrayList<>();
        for (Job j : jobs) if (Job.APPLIED.equals(j.status) || Job.APPROVED.equals(j.status) || Job.REPLIED.equals(j.status)) rows.add(j);
        rows.sort((a, b) -> date(b).compareTo(date(a)));
        StringBuilder sb = new StringBuilder("﻿").append(HEADER).append("\r\n");
        for (Job j : rows) {
            sb.append(cell(date(j))).append(',')
              .append(cell(j.company)).append(',')
              .append(cell(j.title)).append(',')
              .append(cell(j.source)).append(',')
              .append(cell(label(j.status))).append(',')
              .append(cell(link(j))).append("\r\n");
        }
        return sb.toString();
    }

    static String label(String status) {
        return Job.APPLIED.equals(status) ? "Applied" : Job.REPLIED.equals(status) ? "Replied" : "Approved";
    }

    /** The day the status last changed (when you clicked), else the day the job was found. */
    static String date(Job j) {
        String d = j.statusAt == null || j.statusAt.isBlank() ? j.foundAt : j.statusAt;
        return d == null ? "" : (d.length() >= 10 ? d.substring(0, 10) : d);
    }

    /** Only http(s) links are exported. */
    static String link(Job j) {
        String u = !Job.nz(j.applyUrl).isBlank() ? j.applyUrl : j.url;
        return u != null && u.matches("(?i)^https?://\\S+$") ? u : "";
    }

    /** Quotes the cell, doubles inner quotes, and defuses spreadsheet formulas (=, +, -, @). */
    static String cell(String s) {
        String v = s == null ? "" : s.replace("\r", " ").replace("\n", " ").trim();
        if (!v.isEmpty() && "=+-@\t".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
