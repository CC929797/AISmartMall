package com.easymall.files;

import com.easymall.entity.config.AppConfig;
import com.easymall.exception.BusinessException;
import com.easymall.security.CurrentUser;
import com.easymall.utils.FileUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileUtilsSecurityTest {
    @TempDir(cleanup = CleanupMode.NEVER) Path folder;
    FileUtils files;
    ImagePolicy policy;
    ImageUploadQuota quota;

    @BeforeEach void setup() {
        files = new FileUtils();
        AppConfig config = mock(AppConfig.class);
        when(config.getProjectFolder()).thenReturn(folder.toString());
        policy = new ImagePolicy();
        quota = mock(ImageUploadQuota.class);
        ReflectionTestUtils.setField(files, "appConfig", config);
        ReflectionTestUtils.setField(files, "imagePolicy", policy);
        ReflectionTestUtils.setField(files, "imageUploadQuota", quota);
        authenticate(CurrentUser.Role.USER);
    }
    @AfterEach void reset() { RequestContextHolder.resetRequestAttributes(); }
    void authenticate(CurrentUser.Role role) {
        var request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.class.getName(), new CurrentUser("fixture-user", null, null, role));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    byte[] image(String format, int width, int height) throws IOException {
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, bytes));
        return bytes.toByteArray();
    }
    MockMultipartFile upload(String name, byte[] bytes) { return new MockMultipartFile("file", name, "image/png", bytes); }

    @ParameterizedTest @ValueSource(strings = {"photo.png", "photo.jpg", "photo.jpeg", "photo.PNG"})
    void validImagesAreReencodedWithServerNamesAndCanBeRead(String name) throws Exception {
        String format = name.toLowerCase().endsWith("png") ? "png" : "jpg";
        byte[] input = image(format, 400, 200);
        var combined = new ByteArrayOutputStream();
        combined.write(input);
        combined.write("<script>fixture-secret</script>".getBytes());
        String source = files.uploadImage(upload(name, combined.toByteArray()), true);
        assertTrue(source.matches("[0-9]{6}/[a-f0-9]{32}\\.(png|jpg)"));
        byte[] saved = Files.readAllBytes(folder.resolve("file").resolve(source));
        assertFalse(new String(saved, java.nio.charset.StandardCharsets.ISO_8859_1).contains("fixture-secret"));
        assertEquals(400, ImageIO.read(new ByteArrayInputStream(saved)).getWidth());
        String small = source.replace("." + format, "_thumbnail." + format);
        BufferedImage thumb = ImageIO.read(folder.resolve("file").resolve(small).toFile());
        assertEquals(200, thumb.getWidth());
        assertEquals(100, thumb.getHeight());
        var response = new MockHttpServletResponse();
        files.readImage(response, source);
        assertArrayEquals(saved, response.getContentAsByteArray());
        assertEquals(format.equals("png") ? "image/png" : "image/jpeg", response.getContentType());
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        verify(quota).reserveBytes(any(), eq((long) saved.length + Files.size(folder.resolve("file").resolve(small))));
    }

    @Test void anonymousDirectCallsAreRejectedBeforeStorageAndQuota() {
        RequestContextHolder.resetRequestAttributes();
        assertEquals(901, assertThrows(BusinessException.class,
                () -> files.uploadImage(upload("x.png", new byte[]{1}), false)).getCode());
        verifyNoInteractions(quota);
        assertFalse(Files.exists(folder.resolve("file")));
    }
    @Test void trustedAdminUsesSameStorageRules() throws Exception {
        authenticate(CurrentUser.Role.ADMIN);
        assertNotNull(files.uploadImage(upload("x.png", image("png", 2, 2)), false));
    }
    @ParameterizedTest @ValueSource(strings = {"x.svg", "x.gif", "x.webp", "x.bmp", "x.html", "x.exe", "x.png.exe", "x"})
    void unsupportedExtensionsNeverWrite(String name) throws Exception {
        assertThrows(BusinessException.class, () -> files.uploadImage(upload(name, image("png", 2, 2)), false));
        assertFalse(Files.exists(folder.resolve("file")));
        verify(quota, never()).reserveBytes(any(), anyLong());
    }
    @ParameterizedTest @ValueSource(strings = {"<svg onload='alert(1)'/>", "<html><script>alert(1)</script></html>", "not-a-picture"})
    void forgedMimeAndSuffixCannotMakeNonImagesValid(String content) {
        assertThrows(BusinessException.class, () -> files.uploadImage(upload("fake.png", content.getBytes()), false));
        assertFalse(Files.exists(folder.resolve("file")));
    }
    @Test void realFormatMustMatchAllowedExtension() throws Exception {
        assertThrows(BusinessException.class, () -> files.uploadImage(upload("fake.jpg", image("png", 3, 3)), false));
    }
    @Test void emptyAndOversizedFilesAreRejected() {
        assertThrows(BusinessException.class, () -> files.uploadImage(upload("x.png", new byte[0]), false));
        assertThrows(BusinessException.class, () -> files.uploadImage(upload("x.png", new byte[policy.getMaxBytes() + 1]), false));
        assertFalse(Files.exists(folder.resolve("file")));
    }
    @Test void lyingFileSizeCannotBypassStreamLimit() {
        var file = new MockMultipartFile("file", "x.png", "image/png", new byte[policy.getMaxBytes() + 1]) {
            @Override public long getSize() { return 1; }
        };
        assertThrows(BusinessException.class, () -> files.uploadImage(file, false));
        verify(quota, never()).reserveBytes(any(), anyLong());
    }
    @Test void dimensionIsCheckedBeforeFullDecode() throws Exception {
        byte[] bytes = image("png", 4097, 1);
        assertThrows(BusinessException.class, () -> files.uploadImage(upload("x.png", bytes), false));
        assertFalse(Files.exists(folder.resolve("file")));
    }
    @Test void totalPixelsHaveIndependentLimit() throws Exception {
        policy.setMaxPixels(6);
        assertThrows(BusinessException.class, () -> files.uploadImage(upload("x.png", image("png", 3, 3)), false));
    }
    @Test void quotaRejectionCannotCreateFiles() throws Exception {
        doThrow(new BusinessException("合成配额拒绝")).when(quota).reserveBytes(any(), anyLong());
        assertThrows(BusinessException.class, () -> files.uploadImage(upload("x.png", image("png", 3, 3)), true));
        assertFalse(Files.exists(folder.resolve("file")));
    }
    @Test void rateRejectionDoesNotDecodeOrWrite() {
        doThrow(new BusinessException("合成限频拒绝")).when(quota).checkAttempt(any());
        assertThrows(BusinessException.class, () -> files.uploadImage(upload("x.png", new byte[]{1}), true));
        verify(quota, never()).reserveBytes(any(), anyLong());
    }
    @ParameterizedTest @ValueSource(strings = {"../outside.png", "/outside.png", "C:/outside.png", "a/../../outside.png",
            "a\\outside.png", "%2e%2e/outside.png", "a.png:secret", "a.svg", "a.html", "a//b.png", "a/./b.png"})
    void traversalAndNonImagePathsNeverOutputBytes(String source) {
        var response = new MockHttpServletResponse();
        assertThrows(BusinessException.class, () -> files.readImage(response, source));
        assertEquals(0, response.getContentAsByteArray().length);
    }
    @Test void missingResourceIs404() throws Exception {
        var response = new MockHttpServletResponse();
        files.readImage(response, "missing.png");
        assertEquals(404, response.getStatus());
    }
    @Test void existingHtmlWithPngExtensionIsNotServed() throws Exception {
        Files.createDirectory(folder.resolve("file"));
        Files.writeString(folder.resolve("file/fake.png"), "<html><script>alert(1)</script>");
        var response = new MockHttpServletResponse();
        assertThrows(BusinessException.class, () -> files.readImage(response, "fake.png"));
        assertEquals(0, response.getContentAsByteArray().length);
    }
    @Test void existingLegacyGifStillDisplays() throws Exception {
        Files.createDirectory(folder.resolve("file"));
        Files.write(folder.resolve("file/legacy.gif"), image("gif", 2, 2));
        var response = new MockHttpServletResponse();
        files.readImage(response, "legacy.gif");
        assertEquals("image/gif", response.getContentType());
        assertTrue(response.getContentAsByteArray().length > 0);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void directoryLinksCannotEscapeImageRoot(boolean linkRoot) throws Exception {
        Path outside = Files.createDirectory(folder.resolve("outside"));
        Files.write(outside.resolve("fixture.png"), image("png", 2, 2));
        Path root = folder.resolve("file");
        Path link;
        if (linkRoot) {
            link = root;
        } else {
            Files.createDirectory(root);
            link = root.resolve("linked");
        }
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), outside.toString())
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        } else {
            Files.createSymbolicLink(link, outside);
        }
        var response = new MockHttpServletResponse();
        assertThrows(BusinessException.class, () -> files.readImage(response, linkRoot ? "fixture.png" : "linked/fixture.png"));
        assertEquals(0, response.getContentAsByteArray().length);
        if (linkRoot) {
            assertThrows(BusinessException.class, () -> files.uploadImage(upload("x.png", image("png", 2, 2)), false));
        }
    }
}
