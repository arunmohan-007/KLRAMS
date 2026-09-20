package com.fist.rmms_backend;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-process memory breakdown (postgres / nginx / java / ...) for the
 * Monitoring dashboard's "Memory by process" panel.
 *
 * <p>{@link SystemMetricsService} reads the JDK's own
 * {@link com.sun.management.OperatingSystemMXBean}, which only ever reports
 * on the JVM's own host-wide totals — there is no portable JDK API that
 * breaks memory down by process name. Getting that breakdown means shelling
 * out to {@code ps}, which only exists on the Linux production box (the
 * Ubuntu droplet / the Hostinger KVM), not the Windows dev machine — so this
 * degrades to an empty, "not supported" list on Windows rather than failing.
 */
@Service
public class ProcessMemoryService {

    private final boolean supported =
            System.getProperty("os.name", "").toLowerCase().contains("nux");

    public boolean supported() { return supported; }

    /** Top processes by RSS, aggregated by command name (a name can have several PIDs). */
    public List<Map<String, Object>> topByMemory(int limit) {
        if (!supported) return List.of();
        try {
            Process ps = new ProcessBuilder("ps", "-eo", "comm=,rss=").start();
            Map<String, Long> rssKbByName = new TreeMap<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    int sp = line.lastIndexOf(' ');
                    if (sp < 0) continue;
                    String name = line.substring(0, sp).trim();
                    long rssKb;
                    try {
                        rssKb = Long.parseLong(line.substring(sp + 1).trim());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    rssKbByName.merge(name, rssKb, Long::sum);
                }
            }
            ps.waitFor(3, TimeUnit.SECONDS);
            ps.destroyForcibly();

            List<Map.Entry<String, Long>> sorted = new ArrayList<>(rssKbByName.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<String, Long> e : sorted.subList(0, Math.min(limit, sorted.size()))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.getKey());
                m.put("memMb", Math.round(e.getValue() / 1024.0 * 10) / 10.0);
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            System.out.println("[ProcessMemoryService] ps read failed: " + e.getMessage());
            return List.of();
        }
    }
}
