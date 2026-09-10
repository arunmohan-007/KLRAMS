package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the video catalogue will and will not accept in its {@code video_file} column.
 *
 * <p>The failure this guards against is a quiet one: a wrong link imports cleanly and
 * only shows up later as a dead player, on a different page, for whoever happens to
 * click that road. So the checks have to be strict enough to catch the three ways it
 * really goes wrong — plain http, a share page, a path — while still accepting the
 * ordinary object-storage URLs the department will actually use, including ones with
 * no file extension at all.
 */
class VideoRefValidationTest {

    private static void ok(String ref) {
        assertNull(VideoService.checkVideoRef(ref), "should have been accepted: " + ref);
    }

    private static void bad(String ref, String expectedFragment) {
        String problem = VideoService.checkVideoRef(ref);
        assertNotNull(problem, "should have been rejected: " + ref);
        assertTrue(problem.toLowerCase().contains(expectedFragment.toLowerCase()),
                "message for \"" + ref + "\" should mention \"" + expectedFragment + "\" but was: " + problem);
    }

    @Test
    void plainFileNamesAreFine() {
        ok("SH_1_1_2_L1_S1_0+000-Romdas360-0.webm");
        ok("survey front.mp4");
        ok("video1.MP4");
    }

    @Test
    void directHttpsLinksAreFine() {
        ok("https://klrams-video.blr1.digitaloceanspaces.com/sh1_front.mp4");
        ok("https://cdn.example.org/a/b/c/road.webm");
        ok("https://klrams.fist.social/nas-videos/sh1_front.mp4");   // reverse-proxied NAS
    }

    /** Object stores often serve media from an extensionless or signed path. */
    @Test
    void httpsLinksWithoutAFileExtensionAreStillAccepted() {
        ok("https://cdn.example.org/media/9f3c1a2b");
        ok("https://cdn.example.org/stream?id=1234&fmt=mp4");
    }

    @Test
    void plainHttpIsRejected() {
        bad("http://cdn.example.org/road.mp4", "https://");
        bad("http://192.168.1.50/videos/road.mp4", "https://");
    }

    @Test
    void otherSchemesAreRejected() {
        bad("ftp://files.example.org/road.mp4", "only https");
        bad("file://C:/videos/road.mp4", "only https");
    }

    /** The mistake most likely to be made in practice. */
    @Test
    void sharePagesAreRejected() {
        bad("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "share link");
        bad("https://youtu.be/dQw4w9WgXcQ", "share link");
        bad("https://drive.google.com/file/d/1A2B3C/view?usp=sharing", "share link");
        bad("https://1drv.ms/v/s!AbCdEf", "share link");
        bad("https://www.dropbox.com/s/abc123/road.mp4?dl=0", "share link");
        bad("https://vimeo.com/123456789", "share link");
    }

    /** Sub-domains of a share host count too (…​.sharepoint.com). */
    @Test
    void shareHostSubdomainsAreRejected() {
        bad("https://keralapwd.sharepoint.com/sites/rmms/road.mp4", "share link");
        bad("https://player.vimeo.com/video/123456789", "share link");
    }

    @Test
    void pageUrlsAreRejected() {
        bad("https://cdn.example.org/watch.html", "web page");
        bad("https://cdn.example.org/player.php", "web page");
    }

    @Test
    void pathsInsteadOfFileNamesAreRejected() {
        bad("../../etc/passwd", "path");
        bad("subfolder/road.mp4", "path");
        bad("C:\\videos\\road.mp4", "path");
    }

    @Test
    void emptyIsRejected() {
        bad("", "empty");
        bad("   ", "empty");
        assertNotNull(VideoService.checkVideoRef(null));
    }

    /** A dot in a directory name must not be read as the file's extension. */
    @Test
    void extensionIsTakenFromTheLastPathSegment() {
        ok("https://cdn.example.org/v1.2/road");     // ".2/road" is not an extension
    }
}
