package com.easymall.utils;

import com.easymall.entity.config.AppConfig;
import com.easymall.entity.enums.ResponseCodeEnum;
import com.easymall.exception.BusinessException;
import com.easymall.files.ImagePolicy;
import com.easymall.files.ImageUploadQuota;
import com.easymall.security.CurrentUser;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** 图片存储适配器：先校验、解码和重编码，再写入受控目录。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "jakarta.servlet.http.HttpServletResponse")
public class FileUtils {
    @Resource private AppConfig appConfig;
    @Resource private ImagePolicy imagePolicy;
    @Resource private ImageUploadQuota imageUploadQuota;
    // 限制同时解码的图片数量，避免多个合法大图片同时耗尽堆内存。
    private final Semaphore encoders = new Semaphore(2);

    /**
     * 上传图片
     * @param file 文件
     * @param createThumbnail 是否创建缩略图
     * @return 图片路径
     */
    public String uploadImage(MultipartFile file, Boolean createThumbnail) throws IOException {
        CurrentUser user = currentUser();
        imageUploadQuota.checkAttempt(user);
        if (file == null || file.isEmpty() || file.getSize() > imagePolicy.getMaxBytes()) {
            throw new BusinessException("图片不能为空且不能超过5 MiB");
        }
        String original = file.getOriginalFilename();
        String extension = original == null ? "" : original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("jpg", "jpeg", "png").contains(extension)) {
            throw invalidImage();
        }
        if (!encoders.tryAcquire()) {
            throw new BusinessException("图片处理繁忙，请稍后再试");
        }
        try {
            byte[] input;
            try (InputStream stream = file.getInputStream()) { input = stream.readNBytes(imagePolicy.getMaxBytes() + 1); }
            if (input.length == 0 || input.length > imagePolicy.getMaxBytes()) {
                throw invalidImage();
            }
            BufferedImage image = decode(input, extension);
            String format = extension.equals("png") ? "png" : "jpg";
            byte[] encoded = encode(image, format);
            byte[] thumbnail = Boolean.TRUE.equals(createThumbnail) ? encode(thumbnail(image), format) : null;
            // 原图与缩略图按实际落盘字节计量；落盘失败也保留额度，避免失败重试滥用。
            imageUploadQuota.reserveBytes(user, encoded.length + (thumbnail == null ? 0 : thumbnail.length));
            String folder = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
            String name = UUID.randomUUID().toString().replace("-", "");
            String source = folder + "/" + name + "." + format;
            Path root = imageRoot(true);
            Path directory = root.resolve(folder);
            try { Files.createDirectory(directory); } catch (FileAlreadyExistsException ignored) { }
            checked(root, directory);
            Path target = directory.resolve(name + "." + format);
            boolean wrote = false;
            try {
                writeNew(target, encoded);
                wrote = true;
                if (thumbnail != null) {
                    writeNew(directory.resolve(name + "_thumbnail." + format), thumbnail);
                }
            } catch (IOException e) {
                // 仅清理本次已创建的明确文件，不遍历或批量删除目录。
                if (wrote) {
                    Files.deleteIfExists(target);
                }
                throw e;
            }
            return source;
        } finally { encoders.release(); }
    }

    /**
     * 获取当前用户
     * @return 当前用户
     * @throws BusinessException 如果未登录
     */
    private CurrentUser currentUser() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(CurrentUser.class.getName()) instanceof CurrentUser user
                && user.userId() != null && !user.userId().isBlank() && user.role() != null) {
            return user;
        }
        throw new BusinessException(ResponseCodeEnum.CODE_901);
    }

    /**
     * 解码图片
     * @param bytes 图片字节数组
     * @param extension 扩展名
     * @return 解码后的图片
     * @throws BusinessException 如果图片无效
     */
    private BufferedImage decode(byte[] bytes, String extension) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage();
            }
            ImageReader reader = readers.next();
            try {
                String actual = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(actual.equals("png") && extension.equals("png"))
                        && !(Set.of("jpg", "jpeg").contains(actual) && Set.of("jpg", "jpeg").contains(extension))) {
                    throw invalidImage();
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > imagePolicy.getMaxDimension()
                        || height > imagePolicy.getMaxDimension() || (long) width * height > imagePolicy.getMaxPixels()) {
                    throw new BusinessException("图片尺寸或总像素超过限制");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw invalidImage();
                }
                // 只复制像素，不保留原始 EXIF、文本元数据或尾随内容。
                BufferedImage clean = new BufferedImage(width, height,
                        actual.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = clean.createGraphics();
                try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); }
                return clean;
            } finally { reader.dispose(); }
        } catch (IOException e) { throw invalidImage(); }
    }

    /**
     * 编码图片
     * @param image 图片
     * @param format 格式
     * @return 编码后的字节数组
     * @throws BusinessException 如果编码失败
     */
    private byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new FilterOutputStream(bytes) {
            private int count;
            @Override public void write(int b) throws IOException { check(1); out.write(b); }
            @Override public void write(byte[] b, int offset, int length) throws IOException { check(length); out.write(b, offset, length); }
            private void check(int amount) throws IOException {
                if ((long) count + amount > imagePolicy.getMaxBytes()) {
                    throw new IOException("重编码图片超过大小限制");
                }
                count += amount;
            }
        };
        try {
            // 使用内存输出流，不创建 ImageIO 的全局磁盘缓存文件。
            try (var output = new javax.imageio.stream.MemoryCacheImageOutputStream(bounded)) {
                if (!ImageIO.write(image, format, output)) {
                    throw invalidImage();
                }
            }
        } catch (IOException e) { throw new BusinessException("重编码图片超过限制或处理失败"); }
        return bytes.toByteArray();
    }

    /**
     * 生成缩略图
     * @param source 原图
     * @return 缩略图
     */
    private BufferedImage thumbnail(BufferedImage source) {
        double scale = Math.min(1.0, 200.0 / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage result = new BufferedImage(width, height, source.getType());
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return result;
    }

    /**
     * 获取图片存储目录
     * @param create 是否创建目录
     * @return 存储目录
     * @throws IOException 如果获取目录失败
     */
    private Path imageRoot(boolean create) throws IOException {
        String folder = appConfig.getProjectFolder();
        if (folder == null || folder.isBlank()) {
            throw new BusinessException("尚未配置图片存储目录");
        }
        Path root = Path.of(folder).toAbsolutePath().normalize().resolve("file");
        if (create) {
            Files.createDirectories(root.getParent());
        }
        Path parent = root.getParent().toRealPath();
        root = parent.resolve("file");
        if (create) {
            try {
                Files.createDirectory(root);
            } catch (FileAlreadyExistsException ignored) {

            }
        }
        checked(parent, root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidPath();
        }
        return root;
    }

    /**
     * 检查路径是否在允许范围内
     * @param root 允许范围
     * @param candidate 待检查路径
     * @return 绝对路径
     * @throws IOException 如果路径不在允许范围内
     */
    private Path checked(Path root, Path candidate) throws IOException {
        if (!candidate.normalize().startsWith(root) || candidate.equals(root)) {
            throw invalidPath();
        }
        Path current = root;
        for (Path segment : root.relativize(candidate)) {
            current = current.resolve(segment);
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // 拒绝符号链接和 Windows 目录联接等重解析对象。
            if (attributes.isSymbolicLink() || attributes.isOther() || !current.toRealPath().equals(current)) {
                throw invalidPath();
            }
        }
        return candidate;
    }

    /**
     * 以独占方式写入文件，如果写入失败则删除文件
     * @param target 目标文件
     * @param bytes 文件内容
     * @throws IOException 如果写入失败
     */
    private void writeNew(Path target, byte[] bytes) throws IOException {
        OutputStream opened = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean complete = false;
        try {
            try (OutputStream output = opened) {
                output.write(bytes);
            }
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(target);
            }
        }
    }

    /**
     * 读取图片文件
     * @param response 响应
     * @param sourceName 文件名
     * @throws IOException 如果读取失败
     */
    public void readImage(HttpServletResponse response, String sourceName) throws IOException {
        if (sourceName == null || sourceName.length() > 300 || !sourceName.matches(
                "(?:[A-Za-z0-9_-]{1,80}/){0,4}[A-Za-z0-9_-]{1,120}\\.(?i:jpg|jpeg|png|gif|bmp|webp)")) {
            throw invalidPath();
        }
        Path root;
        Path candidate;
        try {
            root = imageRoot(false);
            candidate = checked(root, root.resolve(sourceName).normalize());
        }
        catch (NoSuchFileException e) {
            response.setStatus(404); return;
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidPath();
        }
        try (SeekableByteChannel channel = Files.newByteChannel(candidate, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer header = ByteBuffer.allocate(16);
            channel.read(header);
            String mime = imageMime(header.array(), sourceName);
            response.setContentType(mime);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
            response.setHeader("Cache-Control", "public, max-age=86400");
            response.setContentLengthLong(channel.size());
            channel.position(0);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                response.getOutputStream().write(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
        }
    }

    /**
     * 根据文件头和扩展名判断图片类型
     * @param b 文件头
     * @param name 文件名
     * @return MIME 类型
     * @throws BusinessException 如果图片类型无效
     */
    private String imageMime(byte[] b, String name) {
        String suffix = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (suffix.equals("png") && b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 13 && b[5] == 10 && b[6] == 26 && b[7] == 10) {
            return "image/png";
        }
        if ((suffix.equals("jpg") || suffix.equals("jpeg")) && b[0] == (byte) 0xff && b[1] == (byte) 0xd8 && b[2] == (byte) 0xff) {
            return "image/jpeg";
        }
        if (suffix.equals("gif") && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8' && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return "image/gif";
        }
        if (suffix.equals("bmp") && b[0] == 'B' && b[1] == 'M') {
            return "image/bmp";
        }
        if (suffix.equals("webp") && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }

        throw invalidImage();
    }

    /**
     * 无效图片
     * @return 业务异常
     */
    private BusinessException invalidImage() {
        return new BusinessException("仅支持真实有效的JPG/JPEG、PNG图片");
    }
    /**
     * 无效路径
     * @return 业务异常
     */
    private BusinessException invalidPath() {
        return new BusinessException(ResponseCodeEnum.CODE_600);
    }
}
