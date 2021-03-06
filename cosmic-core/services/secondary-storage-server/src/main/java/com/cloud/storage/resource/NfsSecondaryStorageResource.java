package com.cloud.storage.resource;

import static com.cloud.utils.StringUtils.join;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.apache.commons.lang.StringUtils.substringAfterLast;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthAnswer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.ComputeChecksumCommand;
import com.cloud.agent.api.DeleteSnapshotsDirCommand;
import com.cloud.agent.api.GetStorageStatsAnswer;
import com.cloud.agent.api.GetStorageStatsCommand;
import com.cloud.agent.api.PingCommand;
import com.cloud.agent.api.PingStorageCommand;
import com.cloud.agent.api.ReadyAnswer;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.SecStorageFirewallCfgCommand;
import com.cloud.agent.api.SecStorageFirewallCfgCommand.PortConfig;
import com.cloud.agent.api.SecStorageSetupAnswer;
import com.cloud.agent.api.SecStorageSetupCommand;
import com.cloud.agent.api.SecStorageVMSetupCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupSecondaryStorageCommand;
import com.cloud.agent.api.storage.CreateEntityDownloadURLCommand;
import com.cloud.agent.api.storage.DeleteEntityDownloadURLCommand;
import com.cloud.agent.api.storage.DownloadAnswer;
import com.cloud.agent.api.storage.ListTemplateAnswer;
import com.cloud.agent.api.storage.ListTemplateCommand;
import com.cloud.agent.api.storage.ListVolumeAnswer;
import com.cloud.agent.api.storage.ListVolumeCommand;
import com.cloud.agent.api.storage.UploadCommand;
import com.cloud.agent.api.to.DataObjectType;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.exception.InternalErrorException;
import com.cloud.framework.security.keystore.KeystoreManager;
import com.cloud.host.Host;
import com.cloud.host.Host.Type;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ServerResourceBase;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StorageLayer;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import com.cloud.storage.command.CopyCmdAnswer;
import com.cloud.storage.command.CopyCommand;
import com.cloud.storage.command.DeleteCommand;
import com.cloud.storage.command.DownloadCommand;
import com.cloud.storage.command.DownloadProgressCommand;
import com.cloud.storage.command.TemplateOrVolumePostUploadCommand;
import com.cloud.storage.command.UploadStatusAnswer;
import com.cloud.storage.command.UploadStatusAnswer.UploadStatus;
import com.cloud.storage.command.UploadStatusCommand;
import com.cloud.storage.template.DownloadManager;
import com.cloud.storage.template.DownloadManagerImpl;
import com.cloud.storage.template.Processor;
import com.cloud.storage.template.Processor.FormatInfo;
import com.cloud.storage.template.QCOW2Processor;
import com.cloud.storage.template.RawImageProcessor;
import com.cloud.storage.template.TARProcessor;
import com.cloud.storage.template.TemplateConstants;
import com.cloud.storage.template.TemplateLocation;
import com.cloud.storage.template.TemplateProp;
import com.cloud.storage.template.UploadEntity;
import com.cloud.storage.template.UploadManager;
import com.cloud.storage.template.UploadManagerImpl;
import com.cloud.storage.template.VhdProcessor;
import com.cloud.storage.to.SnapshotObjectTO;
import com.cloud.storage.to.TemplateObjectTO;
import com.cloud.storage.to.VolumeObjectTO;
import com.cloud.utils.EncryptionUtil;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.InvalidParameterValueException;
import com.cloud.utils.imagestore.ImageStoreUtil;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import com.cloud.vm.SecondaryStorageVm;

import javax.naming.ConfigurationException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NfsSecondaryStorageResource extends ServerResourceBase implements SecondaryStorageResource {

    private static final Logger s_logger = LoggerFactory.getLogger(NfsSecondaryStorageResource.class);

    private static final String TEMPLATE_ROOT_DIR = "template/tmpl";
    private static final String VOLUME_ROOT_DIR = "volumes";
    private static final String POST_UPLOAD_KEY_LOCATION = "/etc/cosmic/agent/ms-psk";
    private final List<String> nfsIps = new ArrayList<>();
    final private String _tmpltpp = "template.properties";
    private final HashMap<String, UploadEntity> uploadEntityStateMap = new HashMap<>();
    protected StorageLayer _storage;
    protected boolean _inSystemVM = false;
    protected DownloadManager _dlMgr;
    protected UploadManager _upldMgr;
    protected String _parent = "/mnt/SecStorage";
    protected String createTemplateFromSnapshotXenScript;
    int _timeout;
    String _instance;
    String _dc;
    String _pod;
    String _guid;
    String _role;
    Map<String, Object> _params;
    boolean _sslCopy = false;
    private String _configSslScr;
    private String _configAuthScr;
    private String _configIpFirewallScr;
    private String _publicIp;
    private String _hostname;
    private String _localgw;
    private String _eth1mask;
    private String _eth1ip;
    private String _storageIp;
    private String _storageNetmask;
    private String _storageGateway;
    private String _ssvmPSK = null;

    public int getTimeout() {
        return _timeout;
    }

    public void setTimeout(final int timeout) {
        _timeout = timeout;
    }

    public void setParentPath(final String path) {
        _parent = path;
    }

    public String getMountingRoot() {
        return _parent;
    }

    public void setInSystemVM(final boolean inSystemVM) {
        _inSystemVM = inSystemVM;
    }

    protected CopyCmdAnswer postProcessing(final File destFile, final String downloadPath, final String destPath, final DataTO srcData, final DataTO destData) throws
            ConfigurationException {
        if (destData.getObjectType() == DataObjectType.SNAPSHOT) {
            final SnapshotObjectTO snapshot = new SnapshotObjectTO();
            snapshot.setPath(destPath + File.separator + destFile.getName());

            final CopyCmdAnswer answer = new CopyCmdAnswer(snapshot);
            return answer;
        }
        // do post processing to unzip the file if it is compressed
        final String scriptsDir = "scripts/storage/secondary";
        final String createTmpltScr = Script.findScript(scriptsDir, "createtmplt.sh");
        if (createTmpltScr == null) {
            throw new ConfigurationException("Unable to find createtmplt.sh");
        }
        s_logger.info("createtmplt.sh found in " + createTmpltScr);
        final String createVolScr = Script.findScript(scriptsDir, "createvolume.sh");
        if (createVolScr == null) {
            throw new ConfigurationException("Unable to find createvolume.sh");
        }
        s_logger.info("createvolume.sh found in " + createVolScr);
        final String script = srcData.getObjectType() == DataObjectType.TEMPLATE ? createTmpltScr : createVolScr;

        final int installTimeoutPerGig = 180 * 60 * 1000;
        long imgSizeGigs = (long) Math.ceil(destFile.length() * 1.0d / (1024 * 1024 * 1024));
        imgSizeGigs++; // add one just in case
        final long timeout = imgSizeGigs * installTimeoutPerGig;

        final String origPath = destFile.getAbsolutePath();
        String extension = null;
        if (srcData.getObjectType() == DataObjectType.TEMPLATE) {
            extension = ((TemplateObjectTO) srcData).getFormat().getFileExtension();
        } else if (srcData.getObjectType() == DataObjectType.VOLUME) {
            extension = ((VolumeObjectTO) srcData).getFormat().getFileExtension();
        }

        final String templateName = UUID.randomUUID().toString();
        final String templateFilename = templateName + "." + extension;
        final Script scr = new Script(script, timeout, s_logger);
        scr.add("-s", Long.toString(imgSizeGigs)); // not used for now
        scr.add("-n", templateFilename);

        scr.add("-t", downloadPath);
        scr.add("-f", origPath); // this is the temporary
        // template file downloaded
        final String result;
        result = scr.execute();

        if (result != null) {
            // script execution failure
            throw new CloudRuntimeException("Failed to run script " + script);
        }

        final String finalFileName = templateFilename;
        final String finalDownloadPath = destPath + File.separator + templateFilename;
        // compute the size of
        final long size = _storage.getSize(downloadPath + File.separator + templateFilename);

        DataTO newDestTO = null;

        if (destData.getObjectType() == DataObjectType.TEMPLATE) {
            final TemplateObjectTO newTemplTO = new TemplateObjectTO();
            newTemplTO.setPath(finalDownloadPath);
            newTemplTO.setName(finalFileName);
            newTemplTO.setSize(size);
            newTemplTO.setPhysicalSize(size);
            newDestTO = newTemplTO;
        } else {
            final VolumeObjectTO newVolTO = new VolumeObjectTO();
            newVolTO.setPath(finalDownloadPath);
            newVolTO.setName(finalFileName);
            newVolTO.setSize(size);
            newDestTO = newVolTO;
        }

        return new CopyCmdAnswer(newDestTO);
    }

    protected Answer copySnapshotToTemplateFromNfsToNfsXenserver(final CopyCommand cmd, final SnapshotObjectTO srcData, final NfsTO srcDataStore, final TemplateObjectTO destData,
                                                                 final NfsTO destDataStore) {
        final String srcMountPoint = getRootDir(srcDataStore.getUrl());
        String snapshotPath = srcData.getPath();
        final int index = snapshotPath.lastIndexOf("/");
        String snapshotName = snapshotPath.substring(index + 1);
        if (!snapshotName.startsWith("VHD-") && !snapshotName.endsWith(".vhd")) {
            snapshotName = snapshotName + ".vhd";
        }
        snapshotPath = snapshotPath.substring(0, index);

        snapshotPath = srcMountPoint + File.separator + snapshotPath;
        final String destMountPoint = getRootDir(destDataStore.getUrl());
        final String destPath = destMountPoint + File.separator + destData.getPath();

        String errMsg = null;
        try {
            _storage.mkdir(destPath);

            final String templateUuid = UUID.randomUUID().toString();
            final String templateName = templateUuid + ".vhd";
            final Script command = new Script(createTemplateFromSnapshotXenScript, cmd.getWait() * 1000, s_logger);
            command.add("-p", snapshotPath);
            command.add("-s", snapshotName);
            command.add("-n", templateName);
            command.add("-t", destPath);
            final String result = command.execute();

            if (result != null && !result.equalsIgnoreCase("")) {
                return new CopyCmdAnswer(result);
            }

            final Map<String, Object> params = new HashMap<>();
            params.put(StorageLayer.InstanceConfigKey, _storage);
            final Processor processor = new VhdProcessor();

            processor.configure("Vhd Processor", params);
            final FormatInfo info = processor.process(destPath, null, templateUuid);

            final TemplateLocation loc = new TemplateLocation(_storage, destPath);
            loc.create(1, true, templateUuid);
            loc.addFormat(info);
            loc.save();
            final TemplateProp prop = loc.getTemplateInfo();
            final TemplateObjectTO newTemplate = new TemplateObjectTO();
            newTemplate.setPath(destData.getPath() + File.separator + templateName);
            newTemplate.setFormat(ImageFormat.VHD);
            newTemplate.setSize(prop.getSize());
            newTemplate.setPhysicalSize(prop.getPhysicalSize());
            newTemplate.setName(templateUuid);
            return new CopyCmdAnswer(newTemplate);
        } catch (final ConfigurationException e) {
            s_logger.debug("Failed to create template from snapshot: " + e.toString());
            errMsg = e.toString();
        } catch (final InternalErrorException e) {
            s_logger.debug("Failed to create template from snapshot: " + e.toString());
            errMsg = e.toString();
        } catch (final IOException e) {
            s_logger.debug("Failed to create template from snapshot: " + e.toString());
            errMsg = e.toString();
        }

        return new CopyCmdAnswer(errMsg);
    }

    protected Answer copySnapshotToTemplateFromNfsToNfs(final CopyCommand cmd, final SnapshotObjectTO srcData, final NfsTO srcDataStore, final TemplateObjectTO destData, final
    NfsTO destDataStore) {

        if (srcData.getHypervisorType() == HypervisorType.XenServer) {
            return copySnapshotToTemplateFromNfsToNfsXenserver(cmd, srcData, srcDataStore, destData, destDataStore);
        } else if (srcData.getHypervisorType() == HypervisorType.KVM) {
            final File srcFile = getFile(srcData.getPath(), srcDataStore.getUrl());
            final File destFile = getFile(destData.getPath(), destDataStore.getUrl());

            final VolumeObjectTO volumeObjectTO = srcData.getVolume();
            ImageFormat srcFormat = null;
            //TODO: the image format should be stored in snapshot table, instead of getting from volume
            if (volumeObjectTO != null) {
                srcFormat = volumeObjectTO.getFormat();
            } else {
                srcFormat = ImageFormat.QCOW2;
            }

            // get snapshot file name
            final String templateName = srcFile.getName();
            // add kvm file extension for copied template name
            final String fileName = templateName + "." + srcFormat.getFileExtension();
            final String destFileFullPath = destFile.getAbsolutePath() + File.separator + fileName;
            s_logger.debug("copy snapshot " + srcFile.getAbsolutePath() + " to template " + destFileFullPath);
            Script.runSimpleBashScript("cp " + srcFile.getAbsolutePath() + " " + destFileFullPath);
            final String metaFileName = destFile.getAbsolutePath() + File.separator + "template.properties";
            final File metaFile = new File(metaFileName);
            try {
                _storage.create(destFile.getAbsolutePath(), "template.properties");
                try ( // generate template.properties file
                      FileWriter writer = new FileWriter(metaFile);
                      BufferedWriter bufferWriter = new BufferedWriter(writer)
                ) {
                    // KVM didn't change template unique name, just used the template name passed from orchestration layer, so no need
                    // to send template name back.
                    bufferWriter.write("uniquename=" + destData.getName());
                    bufferWriter.write("\n");
                    bufferWriter.write("filename=" + fileName);
                    bufferWriter.write("\n");
                    final long size = _storage.getSize(destFileFullPath);
                    bufferWriter.write("size=" + size);

                    /**
                     * Snapshots might be in either QCOW2 or RAW image format
                     *
                     * For example RBD snapshots are in RAW format
                     */
                    Processor processor = null;
                    if (srcFormat == ImageFormat.QCOW2) {
                        processor = new QCOW2Processor();
                    } else if (srcFormat == ImageFormat.RAW) {
                        processor = new RawImageProcessor();
                    } else {
                        throw new ConfigurationException("Unknown image format " + srcFormat.toString());
                    }

                    final Map<String, Object> params = new HashMap<>();
                    params.put(StorageLayer.InstanceConfigKey, _storage);

                    processor.configure("template processor", params);
                    final String destPath = destFile.getAbsolutePath();

                    final FormatInfo info = processor.process(destPath, null, templateName);
                    final TemplateLocation loc = new TemplateLocation(_storage, destPath);
                    loc.create(1, true, destData.getName());
                    loc.addFormat(info);
                    loc.save();

                    final TemplateProp prop = loc.getTemplateInfo();
                    final TemplateObjectTO newTemplate = new TemplateObjectTO();
                    newTemplate.setPath(destData.getPath() + File.separator + fileName);
                    newTemplate.setFormat(srcFormat);
                    newTemplate.setSize(prop.getSize());
                    newTemplate.setPhysicalSize(prop.getPhysicalSize());
                    return new CopyCmdAnswer(newTemplate);
                } catch (final ConfigurationException e) {
                    s_logger.debug("Failed to create template:" + e.toString());
                    return new CopyCmdAnswer(e.toString());
                } catch (final InternalErrorException e) {
                    s_logger.debug("Failed to create template:" + e.toString());
                    return new CopyCmdAnswer(e.toString());
                }
            } catch (final IOException e) {
                s_logger.debug("Failed to create template:" + e.toString());
                return new CopyCmdAnswer(e.toString());
            }
        }

        return new CopyCmdAnswer("");
    }

    protected File getFile(final String path, final String nfsPath) {
        final String filePath = getRootDir(nfsPath) + File.separator + path;
        File f = new File(filePath);
        if (!f.exists()) {
            _storage.mkdirs(filePath);
            f = new File(filePath);
        }
        return f;
    }

    protected Answer createTemplateFromSnapshot(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final DataStoreTO srcDataStore = srcData.getDataStore();
        final DataStoreTO destDataStore = destData.getDataStore();
        if (srcDataStore.getRole() == DataStoreRole.Image || srcDataStore.getRole() == DataStoreRole.ImageCache || srcDataStore.getRole() == DataStoreRole.Primary) {
            if (!(srcDataStore instanceof NfsTO)) {
                s_logger.debug("only support nfs storage as src, when create template from snapshot");
                return Answer.createUnsupportedCommandAnswer(cmd);
            }

            if (destDataStore instanceof NfsTO) {
                return copySnapshotToTemplateFromNfsToNfs(cmd, (SnapshotObjectTO) srcData, (NfsTO) srcDataStore, (TemplateObjectTO) destData, (NfsTO) destDataStore);
            }
        }
        s_logger.debug("Failed to create templat from snapshot");
        return new CopyCmdAnswer("Unsupported prototcol");
    }

    protected Answer execute(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final DataStoreTO srcDataStore = srcData.getDataStore();
        final DataStoreTO destDataStore = destData.getDataStore();

        if (srcData.getObjectType() == DataObjectType.SNAPSHOT && destData.getObjectType() == DataObjectType.TEMPLATE) {
            return createTemplateFromSnapshot(cmd);
        }

        return Answer.createUnsupportedCommandAnswer(cmd);
    }

    private String determineStorageTemplatePath(final String storagePath, final String dataPath) {
        return join(asList(getRootDir(storagePath), dataPath), File.separator);
    }

    protected File downloadFromUrlToNfs(final String url, final NfsTO nfs, final String path, final String name) {
        final HttpClient client = new DefaultHttpClient();
        final HttpGet get = new HttpGet(url);
        try {
            final HttpResponse response = client.execute(get);
            final HttpEntity entity = response.getEntity();
            if (entity == null) {
                s_logger.debug("Faled to get entity");
                throw new CloudRuntimeException("Failed to get url: " + url);
            }

            final String nfsMountPath = getRootDir(nfs.getUrl());

            final String filePath = nfsMountPath + File.separator + path;
            final File directory = new File(filePath);
            if (!directory.exists()) {
                _storage.mkdirs(filePath);
            }
            final File destFile = new File(filePath + File.separator + name);
            if (!destFile.createNewFile()) {
                s_logger.warn("Reusing existing file " + destFile.getPath());
            }
            try (FileOutputStream outputStream = new FileOutputStream(destFile)) {
                entity.writeTo(outputStream);
            } catch (final IOException e) {
                s_logger.debug("downloadFromUrlToNfs:Exception:" + e.getMessage(), e);
            }
            return new File(destFile.getAbsolutePath());
        } catch (final IOException e) {
            s_logger.debug("Faild to get url:" + url + ", due to " + e.toString());
            throw new CloudRuntimeException(e);
        }
    }

    private Answer execute(final DownloadCommand cmd) {
        final DataStoreTO dstore = cmd.getDataStore();
        if (dstore instanceof NfsTO) {
            return _dlMgr.handleDownloadCommand(this, cmd);
        } else {
            return new Answer(cmd, false, "Unsupported image data store: " + dstore);
        }
    }

    private ImageFormat getTemplateFormat(final String filePath) {
        String ext = null;
        final int extensionPos = filePath.lastIndexOf('.');
        final int lastSeparator = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        final int i = lastSeparator > extensionPos ? -1 : extensionPos;
        if (i > 0) {
            ext = filePath.substring(i + 1);
        }
        if (ext != null) {
            if (ext.equalsIgnoreCase("vhd")) {
                return ImageFormat.VHD;
            } else if (ext.equalsIgnoreCase("qcow2")) {
                return ImageFormat.QCOW2;
            } else if (ext.equalsIgnoreCase("tar")) {
                return ImageFormat.TAR;
            } else if (ext.equalsIgnoreCase("img") || ext.equalsIgnoreCase("raw")) {
                return ImageFormat.RAW;
            } else if (ext.equalsIgnoreCase("vdi")) {
                return ImageFormat.VDI;
            }
        }

        return null;
    }

    protected long getVirtualSize(final File file, final ImageFormat format) {
        Processor processor = null;
        try {
            if (format == null) {
                return file.length();
            } else if (format == ImageFormat.QCOW2) {
                processor = new QCOW2Processor();
            } else if (format == ImageFormat.VHD) {
                processor = new VhdProcessor();
            } else if (format == ImageFormat.RAW) {
                processor = new RawImageProcessor();
            }
            if (format == ImageFormat.TAR) {
                processor = new TARProcessor();
            }

            if (processor == null) {
                return file.length();
            }

            final Map<String, Object> params = new HashMap<>();
            params.put(StorageLayer.InstanceConfigKey, _storage);
            processor.configure("template processor", params);
            return processor.getVirtualSize(file);
        } catch (final Exception e) {
            s_logger.warn("Failed to get virtual size of file " + file.getPath() + ", returning file size instead: ", e);
            return file.length();
        }
    }

    public Answer execute(final DeleteSnapshotsDirCommand cmd) {
        final DataStoreTO dstore = cmd.getDataStore();
        if (dstore instanceof NfsTO) {
            final NfsTO nfs = (NfsTO) dstore;
            String relativeSnapshotPath = cmd.getDirectory();
            String parent = getRootDir(nfs.getUrl());

            if (relativeSnapshotPath.startsWith(File.separator)) {
                relativeSnapshotPath = relativeSnapshotPath.substring(1);
            }

            if (!parent.endsWith(File.separator)) {
                parent += File.separator;
            }
            final String absoluteSnapshotPath = parent + relativeSnapshotPath;
            final File snapshotDir = new File(absoluteSnapshotPath);
            String details = null;
            if (!snapshotDir.exists()) {
                details = "snapshot directory " + snapshotDir.getName() + " doesn't exist";
                s_logger.debug(details);
                return new Answer(cmd, true, details);
            }
            // delete all files in the directory
            final String lPath = absoluteSnapshotPath + "/*";
            final String result = deleteLocalFile(lPath);
            if (result != null) {
                final String errMsg = "failed to delete all snapshots " + lPath + " , err=" + result;
                s_logger.warn(errMsg);
                return new Answer(cmd, false, errMsg);
            }
            // delete the directory
            if (!snapshotDir.delete()) {
                details = "Unable to delete directory " + snapshotDir.getName() + " under snapshot path " + relativeSnapshotPath;
                s_logger.debug(details);
                return new Answer(cmd, false, details);
            }
            return new Answer(cmd, true, null);
        } else {
            return new Answer(cmd, false, "Unsupported image data store: " + dstore);
        }
    }

    private Answer execute(final ComputeChecksumCommand cmd) {

        String relativeTemplatePath = cmd.getTemplatePath();
        final DataStoreTO store = cmd.getStore();
        if (!(store instanceof NfsTO)) {
            return new Answer(cmd, false, "can't handle non nfs data store");
        }
        final NfsTO nfsStore = (NfsTO) store;
        String parent = getRootDir(nfsStore.getUrl());

        if (relativeTemplatePath.startsWith(File.separator)) {
            relativeTemplatePath = relativeTemplatePath.substring(1);
        }

        if (!parent.endsWith(File.separator)) {
            parent += File.separator;
        }
        final String absoluteTemplatePath = parent + relativeTemplatePath;
        final MessageDigest digest;
        String checksum = null;
        final File f = new File(absoluteTemplatePath);
        InputStream is = null;
        final byte[] buffer = new byte[8192];
        int read = 0;
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("parent path " + parent + " relative template path " + relativeTemplatePath);
        }

        try {
            digest = MessageDigest.getInstance("MD5");
            is = new FileInputStream(f);
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            final byte[] md5sum = digest.digest();
            final BigInteger bigInt = new BigInteger(1, md5sum);
            checksum = bigInt.toString(16);
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Successfully calculated checksum for file " + absoluteTemplatePath + " - " + checksum);
            }
        } catch (final IOException e) {
            final String logMsg = "Unable to process file for MD5 - " + absoluteTemplatePath;
            s_logger.error(logMsg);
            return new Answer(cmd, false, checksum);
        } catch (final NoSuchAlgorithmException e) {
            return new Answer(cmd, false, checksum);
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (final IOException e) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Could not close the file " + absoluteTemplatePath);
                }
                return new Answer(cmd, false, checksum);
            }
        }

        return new Answer(cmd, true, checksum);
    }

    private void configCerts(final KeystoreManager.Certificates certs) {
        if (certs == null) {
            configureSSL();
        } else {
            final String prvKey = certs.getPrivKey();
            final String pubCert = certs.getPrivCert();
            final String certChain = certs.getCertChain();
            final String rootCACert = certs.getRootCACert();

            try {
                final File prvKeyFile = File.createTempFile("prvkey", null);
                final String prvkeyPath = prvKeyFile.getAbsolutePath();
                try (BufferedWriter prvt_key_file = new BufferedWriter(new FileWriter(prvKeyFile))) {
                    prvt_key_file.write(prvKey);
                } catch (final IOException e) {
                    s_logger.debug("Failed to config ssl: " + e.toString());
                }

                final File pubCertFile = File.createTempFile("pubcert", null);
                final String pubCertFilePath = pubCertFile.getAbsolutePath();

                try (BufferedWriter pub_cert_file = new BufferedWriter(new FileWriter(pubCertFile))) {
                    pub_cert_file.write(pubCert);
                } catch (final IOException e) {
                    s_logger.debug("Failed to config ssl: " + e.toString());
                }

                String certChainFilePath = null, rootCACertFilePath = null;
                File certChainFile = null, rootCACertFile = null;
                if (certChain != null) {
                    certChainFile = File.createTempFile("certchain", null);
                    certChainFilePath = certChainFile.getAbsolutePath();
                    try (BufferedWriter cert_chain_out = new BufferedWriter(new FileWriter(certChainFile))) {
                        cert_chain_out.write(certChain);
                    } catch (final IOException e) {
                        s_logger.debug("Failed to config ssl: " + e.toString());
                    }
                }

                if (rootCACert != null) {
                    rootCACertFile = File.createTempFile("rootcert", null);
                    rootCACertFilePath = rootCACertFile.getAbsolutePath();
                    try (BufferedWriter root_ca_cert_file = new BufferedWriter(new FileWriter(rootCACertFile))) {
                        root_ca_cert_file.write(rootCACert);
                    } catch (final IOException e) {
                        s_logger.debug("Failed to config ssl: " + e.toString());
                    }
                }

                configureSSL(prvkeyPath, pubCertFilePath, certChainFilePath, rootCACertFilePath);

                prvKeyFile.delete();
                pubCertFile.delete();
                if (certChainFile != null) {
                    certChainFile.delete();
                }
                if (rootCACertFile != null) {
                    rootCACertFile.delete();
                }
            } catch (final IOException e) {
                s_logger.debug("Failed to config ssl: " + e.toString());
            }
        }
    }

    private Answer execute(final SecStorageSetupCommand cmd) {
        if (!_inSystemVM) {
            return new Answer(cmd, true, null);
        }
        Answer answer = null;
        final DataStoreTO dStore = cmd.getDataStore();
        if (dStore instanceof NfsTO) {
            final String secUrl = cmd.getSecUrl();
            try {
                final URI uri = new URI(secUrl);
                final String nfsHostIp = getUriHostIp(uri);

                addRouteToInternalIpOrCidr(_storageGateway, _storageIp, _storageNetmask, nfsHostIp);
                final String dir = mountUri(uri);

                configCerts(cmd.getCerts());

                nfsIps.add(nfsHostIp);
                answer = new SecStorageSetupAnswer(dir);
            } catch (final Exception e) {
                final String msg = "GetRootDir for " + secUrl + " failed due to " + e.toString();
                s_logger.error(msg);
                answer = new Answer(cmd, false, msg);
            }
        } else {
            answer = new Answer(cmd, true, null);
        }

        savePostUploadPSK(cmd.getPostUploadKey());
        startPostUploadServer();
        return answer;
    }

    private void startPostUploadServer() {
        final int PORT = 8210;
        final int NO_OF_WORKERS = 15;
        final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        final EventLoopGroup workerGroup = new NioEventLoopGroup(NO_OF_WORKERS);
        final ServerBootstrap b = new ServerBootstrap();
        final NfsSecondaryStorageResource storageResource = this;
        b.group(bossGroup, workerGroup);
        b.channel(NioServerSocketChannel.class);
        b.handler(new LoggingHandler(LogLevel.INFO));
        b.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel ch) throws Exception {
                final ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast(new HttpRequestDecoder());
                pipeline.addLast(new HttpResponseEncoder());
                pipeline.addLast(new HttpContentCompressor());
                pipeline.addLast(new HttpUploadServerHandler(storageResource));
            }
        });
        new Thread() {
            @Override
            public void run() {
                try {
                    final Channel ch = b.bind(PORT).sync().channel();
                    s_logger.info(String.format("Started post upload server on port %d with %d workers", PORT, NO_OF_WORKERS));
                    ch.closeFuture().sync();
                } catch (final InterruptedException e) {
                    s_logger.info("Failed to start post upload server");
                    s_logger.debug("Exception while starting post upload server", e);
                } finally {
                    bossGroup.shutdownGracefully();
                    workerGroup.shutdownGracefully();
                    s_logger.info("shutting down post upload server");
                }
            }
        }.start();
        s_logger.info("created a thread to start post upload server");
    }

    private void savePostUploadPSK(final String psk) {
        try {
            FileUtils.writeStringToFile(new File(POST_UPLOAD_KEY_LOCATION), psk, "utf-8");
        } catch (final IOException ex) {
            s_logger.debug("Failed to copy PSK to the file.", ex);
        }
    }

    protected Answer deleteSnapshot(final DeleteCommand cmd) {
        final DataTO obj = cmd.getData();
        final DataStoreTO dstore = obj.getDataStore();
        if (dstore instanceof NfsTO) {
            final NfsTO nfs = (NfsTO) dstore;
            String parent = getRootDir(nfs.getUrl());
            if (!parent.endsWith(File.separator)) {
                parent += File.separator;
            }
            String snapshotPath = obj.getPath();
            if (snapshotPath.startsWith(File.separator)) {
                snapshotPath = snapshotPath.substring(1);
            }
            // check if the passed snapshot path is a directory or not. For ImageCache, path is stored as a directory instead of
            // snapshot file name. If so, since backupSnapshot process has already deleted snapshot in cache, so we just do nothing
            // and return true.
            final String fullSnapPath = parent + snapshotPath;
            final File snapDir = new File(fullSnapPath);
            if (snapDir.exists() && snapDir.isDirectory()) {
                s_logger.debug("snapshot path " + snapshotPath + " is a directory, already deleted during backup snapshot, so no need to delete");
                return new Answer(cmd, true, null);
            }
            // passed snapshot path is a snapshot file path, then get snapshot directory first
            final int index = snapshotPath.lastIndexOf("/");
            final String snapshotName = snapshotPath.substring(index + 1);
            snapshotPath = snapshotPath.substring(0, index);
            final String absoluteSnapshotPath = parent + snapshotPath;
            // check if snapshot directory exists
            final File snapshotDir = new File(absoluteSnapshotPath);
            String details = null;
            if (!snapshotDir.exists()) {
                details = "snapshot directory " + snapshotDir.getName() + " doesn't exist";
                s_logger.debug(details);
                return new Answer(cmd, true, details);
            }
            // delete snapshot in the directory if exists
            final String lPath = absoluteSnapshotPath + "/*" + snapshotName + "*";
            final String result = deleteLocalFile(lPath);
            if (result != null) {
                details = "failed to delete snapshot " + lPath + " , err=" + result;
                s_logger.warn(details);
                return new Answer(cmd, false, details);
            }
            return new Answer(cmd, true, null);
        } else {
            return new Answer(cmd, false, "Unsupported image data store: " + dstore);
        }
    }

    private Answer execute(final ListTemplateCommand cmd) {
        if (!_inSystemVM) {
            return new ListTemplateAnswer(null, null);
        }

        final DataStoreTO store = cmd.getDataStore();
        if (store instanceof NfsTO) {
            final NfsTO nfs = (NfsTO) store;
            final String secUrl = nfs.getUrl();
            final String root = getRootDir(secUrl);
            final Map<String, TemplateProp> templateInfos = _dlMgr.gatherTemplateInfo(root);
            return new ListTemplateAnswer(secUrl, templateInfos);
        } else {
            return new Answer(cmd, false, "Unsupported image data store: " + store);
        }
    }

    private Answer execute(final ListVolumeCommand cmd) {
        if (!_inSystemVM) {
            return new ListVolumeAnswer(cmd.getSecUrl(), null);
        }
        final DataStoreTO store = cmd.getDataStore();
        if (store instanceof NfsTO) {
            final String root = getRootDir(cmd.getSecUrl());
            final Map<Long, TemplateProp> templateInfos = _dlMgr.gatherVolumeInfo(root);
            return new ListVolumeAnswer(cmd.getSecUrl(), templateInfos);
        } else {
            return new Answer(cmd, false, "Unsupported image data store: " + store);
        }
    }

    private Answer execute(final SecStorageVMSetupCommand cmd) {
        if (!_inSystemVM) {
            return new Answer(cmd, true, null);
        }
        boolean success = true;
        final StringBuilder result = new StringBuilder();
        for (final String cidr : cmd.getAllowedInternalSites()) {
            if (nfsIps.contains(cidr)) {
        /*
         * if the internal download ip is the same with secondary
         * storage ip, adding internal sites will flush ip route to nfs
         * through storage ip.
         */
                continue;
            }
            final String tmpresult = allowOutgoingOnPrivate(cidr);
            if (tmpresult != null) {
                result.append(", ").append(tmpresult);
                success = false;
            }
        }

        for (final String cidr : cmd.getAllowedExternalCidrs()) {
            final String tmpresult = this.setExternalAccess(cidr);
            if (tmpresult != null) {
                result.append(", ").append(tmpresult);
                success = false;
            }
        }

        if (success) {
            if (cmd.getCopyPassword() != null && cmd.getCopyUserName() != null) {
                final String tmpresult = configureAuth(cmd.getCopyUserName(), cmd.getCopyPassword());
                if (tmpresult != null) {
                    result.append("Failed to configure auth for copy ").append(tmpresult);
                    success = false;
                }
            }
        }
        return new Answer(cmd, success, result.toString());
    }

    private String deleteLocalFile(final String fullPath) {
        final Script command = new Script("/bin/bash", s_logger);
        command.add("-c");
        command.add("rm -rf " + fullPath);
        final String result = command.execute();
        if (result != null) {
            final String errMsg = "Failed to delete file " + fullPath + ", err=" + result;
            s_logger.warn(errMsg);
            return errMsg;
        }
        return null;
    }

    public String allowOutgoingOnPrivate(final String destCidr) {
        if (!_inSystemVM) {
            return null;
        }
        final Script command = new Script("/bin/bash", s_logger);
        final String intf = "eth1";
        command.add("-c");
        command.add("iptables -I OUTPUT -o " + intf + " -d " + destCidr
                + " -p tcp -m state --state NEW -m tcp  -j ACCEPT");

        final String result = command.execute();
        if (result != null) {
            s_logger.warn("Error in allowing outgoing to " + destCidr + ", err=" + result);
            return "Error in allowing outgoing to " + destCidr + ", err=" + result;
        }

        addRouteToInternalIpOrCidr(_localgw, _eth1ip, _eth1mask, destCidr);

        return null;
    }

    public String setExternalAccess(final String sourceCidr) {
        if (!_inSystemVM) {
            return null;
        }
        final Script command = new Script("/bin/bash", s_logger);
        final String intf = "eth0";
        command.add("-c");
        command.add("iptables -I INPUT -i " + intf + " -s " + sourceCidr + " -p tcp -m multiport --dports 80,443 -m tcp -j ACCEPT");

        final String result = command.execute();
        if (result != null) {
            s_logger.warn("Error in allowing external firewall to " + sourceCidr + ", err=" + result);
            return "Error in allowing external to " + sourceCidr + ", err=" + result;
        }

        return null;
    }

    private Answer execute(final SecStorageFirewallCfgCommand cmd) {
        if (!_inSystemVM) {
            return new Answer(cmd, true, null);
        }

        final List<String> ipList = new ArrayList<>();

        for (final PortConfig pCfg : cmd.getPortConfigs()) {
            if (pCfg.isAdd()) {
                ipList.add(pCfg.getSourceIp());
            }
        }
        boolean success = true;
        final String result;
        result = configureIpFirewall(ipList, cmd.getIsAppendAIp());
        if (result != null) {
            success = false;
        }

        return new Answer(cmd, success, result);
    }

    private UploadStatusAnswer execute(final UploadStatusCommand cmd) {
        final String entityUuid = cmd.getEntityUuid();
        if (uploadEntityStateMap.containsKey(entityUuid)) {
            final UploadEntity uploadEntity = uploadEntityStateMap.get(entityUuid);
            if (uploadEntity.getUploadState() == UploadEntity.Status.ERROR) {
                uploadEntityStateMap.remove(entityUuid);
                return new UploadStatusAnswer(cmd, UploadStatus.ERROR, uploadEntity.getErrorMessage());
            } else if (uploadEntity.getUploadState() == UploadEntity.Status.COMPLETED) {
                final UploadStatusAnswer answer = new UploadStatusAnswer(cmd, UploadStatus.COMPLETED);
                answer.setVirtualSize(uploadEntity.getVirtualSize());
                answer.setInstallPath(uploadEntity.getTmpltPath());
                answer.setPhysicalSize(uploadEntity.getPhysicalSize());
                answer.setDownloadPercent(100);
                uploadEntityStateMap.remove(entityUuid);
                return answer;
            } else if (uploadEntity.getUploadState() == UploadEntity.Status.IN_PROGRESS) {
                final UploadStatusAnswer answer = new UploadStatusAnswer(cmd, UploadStatus.IN_PROGRESS);
                final long downloadedSize = FileUtils.sizeOfDirectory(new File(uploadEntity.getInstallPathPrefix()));
                final int downloadPercent = (int) (100 * downloadedSize / uploadEntity.getContentLength());
                answer.setDownloadPercent(Math.min(downloadPercent, 100));
                return answer;
            }
        }
        return new UploadStatusAnswer(cmd, UploadStatus.UNKNOWN);
    }

    protected GetStorageStatsAnswer execute(final GetStorageStatsCommand cmd) {
        final DataStoreTO store = cmd.getStore();

        final String rootDir = getRootDir(((NfsTO) store).getUrl());
        final long usedSize = getUsedSize(rootDir);
        final long totalSize = getTotalSize(rootDir);
        if ((usedSize == 0 && totalSize == 0) || usedSize < 0 || totalSize < 0) {
            return new GetStorageStatsAnswer(cmd, "Unable to get storage stats");
        } else {
            return new GetStorageStatsAnswer(cmd, totalSize, usedSize);
        }
    }

    protected Answer execute(final DeleteCommand cmd) {
        final DataTO obj = cmd.getData();
        final DataObjectType objType = obj.getObjectType();
        if (obj.getPath() == null) {
            // account for those fake entries for NFS migration to object store
            return new Answer(cmd, true, "Object with null install path does not exist on image store , no need to delete");
        }
        switch (objType) {
            case TEMPLATE:
                return deleteTemplate(cmd);
            case VOLUME:
                return deleteVolume(cmd);
            case SNAPSHOT:
                return deleteSnapshot(cmd);
        }
        return Answer.createUnsupportedCommandAnswer(cmd);
    }

    protected Answer deleteTemplate(final DeleteCommand cmd) {
        final DataTO obj = cmd.getData();
        final DataStoreTO dstore = obj.getDataStore();
        if (dstore instanceof NfsTO) {
            final NfsTO nfs = (NfsTO) dstore;
            String relativeTemplatePath = obj.getPath();
            String parent = getRootDir(nfs.getUrl());

            if (relativeTemplatePath.startsWith(File.separator)) {
                relativeTemplatePath = relativeTemplatePath.substring(1);
            }

            if (!parent.endsWith(File.separator)) {
                parent += File.separator;
            }
            final String absoluteTemplatePath = parent + relativeTemplatePath;
            final File tmpltPath = new File(absoluteTemplatePath);
            File tmpltParent = null;
            if (tmpltPath.exists() && tmpltPath.isDirectory()) {
                tmpltParent = tmpltPath;
            } else {
                tmpltParent = tmpltPath.getParentFile();
            }

            String details = null;
            if (!tmpltParent.exists()) {
                details = "template parent directory " + tmpltParent.getName() + " doesn't exist";
                s_logger.debug(details);
                return new Answer(cmd, true, details);
            }
            final File[] tmpltFiles = tmpltParent.listFiles();
            if (tmpltFiles == null || tmpltFiles.length == 0) {
                details = "No files under template parent directory " + tmpltParent.getName();
                s_logger.debug(details);
            } else {
                boolean found = false;
                for (final File f : tmpltFiles) {
                    if (!found && f.getName().equals("template.properties")) {
                        found = true;
                    }

                    // KVM HA monitor makes a mess in the templates with its
                    // heartbeat tests
                    // Don't let this stop us from cleaning up the template
                    if (f.isDirectory() && f.getName().equals("KVMHA")) {
                        s_logger.debug("Deleting KVMHA directory contents from template location");
                        final File[] haFiles = f.listFiles();
                        for (final File haFile : haFiles) {
                            haFile.delete();
                        }
                    }

                    if (!f.delete()) {
                        return new Answer(cmd, false, "Unable to delete file " + f.getName() + " under Template path " + relativeTemplatePath);
                    }
                }

                if (!found) {
                    details = "Can not find template.properties under " + tmpltParent.getName();
                    s_logger.debug(details);
                }
            }
            if (!tmpltParent.delete()) {
                details = "Unable to delete directory " + tmpltParent.getName() + " under Template path " + relativeTemplatePath;
                s_logger.debug(details);
                return new Answer(cmd, false, details);
            }
            return new Answer(cmd, true, null);
        } else {
            return new Answer(cmd, false, "Unsupported image data store: " + dstore);
        }
    }

    protected Answer deleteVolume(final DeleteCommand cmd) {
        final DataTO obj = cmd.getData();
        final DataStoreTO dstore = obj.getDataStore();
        if (dstore instanceof NfsTO) {
            final NfsTO nfs = (NfsTO) dstore;
            String relativeVolumePath = obj.getPath();
            String parent = getRootDir(nfs.getUrl());

            if (relativeVolumePath.startsWith(File.separator)) {
                relativeVolumePath = relativeVolumePath.substring(1);
            }

            if (!parent.endsWith(File.separator)) {
                parent += File.separator;
            }
            final String absoluteVolumePath = parent + relativeVolumePath;
            final File volPath = new File(absoluteVolumePath);
            File tmpltParent = null;
            if (volPath.exists() && volPath.isDirectory()) {
                // for vmware, absoluteVolumePath represents a directory where volume files are located.
                tmpltParent = volPath;
            } else {
                // for other hypervisors, the volume .vhd or .qcow2 file path is passed
                tmpltParent = new File(absoluteVolumePath).getParentFile();
            }
            String details = null;
            if (!tmpltParent.exists()) {
                details = "volume parent directory " + tmpltParent.getName() + " doesn't exist";
                s_logger.debug(details);
                return new Answer(cmd, true, details);
            }
            final File[] tmpltFiles = tmpltParent.listFiles();
            if (tmpltFiles == null || tmpltFiles.length == 0) {
                details = "No files under volume parent directory " + tmpltParent.getName();
                s_logger.debug(details);
            } else {
                boolean found = false;
                for (final File f : tmpltFiles) {
                    if (!found && f.getName().equals("volume.properties")) {
                        found = true;
                    }

                    // KVM HA monitor makes a mess in the templates with its
                    // heartbeat tests
                    // Don't let this stop us from cleaning up the template
                    if (f.isDirectory() && f.getName().equals("KVMHA")) {
                        s_logger.debug("Deleting KVMHA directory contents from template location");
                        final File[] haFiles = f.listFiles();
                        for (final File haFile : haFiles) {
                            haFile.delete();
                        }
                    }

                    if (!f.delete()) {
                        return new Answer(cmd, false, "Unable to delete file " + f.getName() + " under Volume path " + tmpltParent.getPath());
                    }
                }
                if (!found) {
                    details = "Can not find volume.properties under " + tmpltParent.getName();
                    s_logger.debug(details);
                }
            }
            if (!tmpltParent.delete()) {
                details = "Unable to delete directory " + tmpltParent.getName() + " under Volume path " + tmpltParent.getPath();
                s_logger.debug(details);
                return new Answer(cmd, false, details);
            }
            return new Answer(cmd, true, null);
        } else {
            return new Answer(cmd, false, "Unsupported image data store: " + dstore);
        }
    }

    @Override
    synchronized public String getRootDir(final String secUrl) {
        if (!_inSystemVM) {
            return _parent;
        }
        try {
            final URI uri = new URI(secUrl);
            final String dir = mountUri(uri);
            return _parent + "/" + dir;
        } catch (final Exception e) {
            final String msg = "GetRootDir for " + secUrl + " failed due to " + e.toString();
            s_logger.error(msg, e);
            throw new CloudRuntimeException(msg);
        }
    }

    protected long getUsedSize(final String rootDir) {
        return _storage.getUsedSpace(rootDir);
    }

    protected long getTotalSize(final String rootDir) {
        return _storage.getTotalSpace(rootDir);
    }

    protected long convertFilesystemSize(final String size) {
        if (size == null || size.isEmpty()) {
            return -1;
        }

        long multiplier = 1;
        if (size.endsWith("T")) {
            multiplier = 1024l * 1024l * 1024l * 1024l;
        } else if (size.endsWith("G")) {
            multiplier = 1024l * 1024l * 1024l;
        } else if (size.endsWith("M")) {
            multiplier = 1024l * 1024l;
        } else {
            assert false : "Well, I have no idea what this is: " + size;
        }

        return (long) (Double.parseDouble(size.substring(0, size.length() - 1)) * multiplier);
    }

    @Override
    public Type getType() {
        if (SecondaryStorageVm.Role.templateProcessor.toString().equals(_role)) {
            return Host.Type.SecondaryStorage;
        }

        return Host.Type.SecondaryStorageCmdExecutor;
    }

    @Override
    public StartupCommand[] initialize() {

        final StartupSecondaryStorageCommand cmd = new StartupSecondaryStorageCommand();
        fillNetworkInformation(cmd);
        cmd.setVersion(NfsSecondaryStorageResource.class.getPackage().getImplementationVersion());
        if (_publicIp != null) {
            cmd.setPublicIpAddress(_publicIp);
        }

        if (_inSystemVM) {
            final Script command = new Script("/bin/bash", s_logger);
            command.add("-c");
            command.add("ln -sf " + _parent + " /var/www/html/copy");
            final String result = command.execute();
            if (result != null) {
                s_logger.warn("Error in linking  err=" + result);
                return null;
            }
        }
        return new StartupCommand[]{cmd};
    }

    @Override
    public PingCommand getCurrentStatus(final long id) {
        return new PingStorageCommand(Host.Type.Storage, id, new HashMap<>());
    }

    @Override
    public Answer executeRequest(final Command cmd) {
        if (cmd instanceof DownloadProgressCommand) {
            return _dlMgr.handleDownloadCommand(this, (DownloadProgressCommand) cmd);
        } else if (cmd instanceof DownloadCommand) {
            return execute((DownloadCommand) cmd);
        } else if (cmd instanceof UploadCommand) {
            return _upldMgr.handleUploadCommand(this, (UploadCommand) cmd);
        } else if (cmd instanceof CreateEntityDownloadURLCommand) {
            return _upldMgr.handleCreateEntityURLCommand((CreateEntityDownloadURLCommand) cmd);
        } else if (cmd instanceof DeleteEntityDownloadURLCommand) {
            return _upldMgr.handleDeleteEntityDownloadURLCommand((DeleteEntityDownloadURLCommand) cmd);
        } else if (cmd instanceof GetStorageStatsCommand) {
            return execute((GetStorageStatsCommand) cmd);
        } else if (cmd instanceof CheckHealthCommand) {
            return new CheckHealthAnswer((CheckHealthCommand) cmd, true);
        } else if (cmd instanceof ReadyCommand) {
            return new ReadyAnswer((ReadyCommand) cmd);
        } else if (cmd instanceof SecStorageFirewallCfgCommand) {
            return execute((SecStorageFirewallCfgCommand) cmd);
        } else if (cmd instanceof SecStorageVMSetupCommand) {
            return execute((SecStorageVMSetupCommand) cmd);
        } else if (cmd instanceof SecStorageSetupCommand) {
            return execute((SecStorageSetupCommand) cmd);
        } else if (cmd instanceof ComputeChecksumCommand) {
            return execute((ComputeChecksumCommand) cmd);
        } else if (cmd instanceof ListTemplateCommand) {
            return execute((ListTemplateCommand) cmd);
        } else if (cmd instanceof ListVolumeCommand) {
            return execute((ListVolumeCommand) cmd);
        } else if (cmd instanceof DeleteSnapshotsDirCommand) {
            return execute((DeleteSnapshotsDirCommand) cmd);
        } else if (cmd instanceof CopyCommand) {
            return execute((CopyCommand) cmd);
        } else if (cmd instanceof DeleteCommand) {
            return execute((DeleteCommand) cmd);
        } else if (cmd instanceof UploadStatusCommand) {
            return execute((UploadStatusCommand) cmd);
        } else {
            return Answer.createUnsupportedCommandAnswer(cmd);
        }
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        s_logger.info("Configuring resource {}", name);

        configurePrivateInterface(params);
        configuringPublicInterface(params);

        _hostname = (String) params.get("name");

        final String inSystemVM = (String) params.get("secondary.storage.vm");
        if (inSystemVM == null || "true".equalsIgnoreCase(inSystemVM)) {
            s_logger.debug("conf secondary.storage.vm is true, act as if executing in SSVM");
            _inSystemVM = true;
        }

        _storageIp = (String) params.get("storageip");
        if (_storageIp == null && _inSystemVM) {
            s_logger.warn("There is no storageip in /proc/cmdline, something wrong!");
        }
        _storageNetmask = (String) params.get("storagenetmask");
        _storageGateway = (String) params.get("storagegateway");
        super.configure(name, params);

        _params = params;
        final String value = (String) params.get("scripts.timeout");
        _timeout = NumbersUtil.parseInt(value, 1440) * 1000;

        _storage = (StorageLayer) params.get(StorageLayer.InstanceConfigKey);
        configureStorageLayerClass(params);

        if (_inSystemVM) {
            _storage.mkdirs(_parent);
        }

        _configSslScr = Script.findScript(getDefaultScriptsDir(), "config_ssl.sh");
        if (_configSslScr != null) {
            s_logger.info("config_ssl.sh found in " + _configSslScr);
        }

        _configAuthScr = Script.findScript(getDefaultScriptsDir(), "config_auth.sh");
        if (_configAuthScr != null) {
            s_logger.info("config_auth.sh found in " + _configAuthScr);
        }

        _configIpFirewallScr = Script.findScript(getDefaultScriptsDir(), "ipfirewall.sh");
        if (_configIpFirewallScr != null) {
            s_logger.info("_configIpFirewallScr found in " + _configIpFirewallScr);
        }

        createTemplateFromSnapshotXenScript = Script.findScript(getDefaultScriptsDir(), "create_privatetemplate_from_snapshot_xen.sh");
        if (createTemplateFromSnapshotXenScript == null) {
            throw new ConfigurationException("create_privatetemplate_from_snapshot_xen.sh not found in " + getDefaultScriptsDir());
        }

        _role = (String) params.get("role");
        if (_role == null) {
            _role = SecondaryStorageVm.Role.templateProcessor.toString();
        }
        s_logger.info("Secondary storage runs in role " + _role);

        _guid = (String) params.get("guid");
        if (_guid == null) {
            throw new ConfigurationException("Unable to find the guid");
        }

        _dc = (String) params.get("zone");
        if (_dc == null) {
            throw new ConfigurationException("Unable to find the zone");
        }
        _pod = (String) params.get("pod");

        _instance = (String) params.get("instance");

        if (!_inSystemVM) {
            _parent = (String) params.get("mount.path");
        }

        if (_inSystemVM) {
            _localgw = (String) params.get("localgw");
            if (_localgw != null) { // can only happen inside service vm
                final String mgmtHost = (String) params.get("host");
                addRouteToInternalIpOrCidr(_localgw, _eth1ip, _eth1mask, mgmtHost);

                final String internalDns1 = (String) params.get("internaldns1");
                if (internalDns1 == null) {
                    s_logger.warn("No DNS entry found during configuration of NfsSecondaryStorage");
                } else {
                    addRouteToInternalIpOrCidr(_localgw, _eth1ip, _eth1mask, internalDns1);
                }

                final String internalDns2 = (String) params.get("internaldns2");
                if (internalDns2 != null) {
                    addRouteToInternalIpOrCidr(_localgw, _eth1ip, _eth1mask, internalDns2);
                }
            }

            startAdditionalServices();
            _params.put("install.numthreads", "50");
            _params.put("secondary.storage.vm", "true");
        }

        try {
            _params.put(StorageLayer.InstanceConfigKey, _storage);
            _dlMgr = new DownloadManagerImpl();
            _dlMgr.configure("DownloadManager", _params);
            _upldMgr = new UploadManagerImpl();
            _upldMgr.configure("UploadManager", params);
        } catch (final ConfigurationException e) {
            s_logger.warn("Caught problem while configuring DownloadManager", e);
            return false;
        }
        return true;
    }

    private void configuringPublicInterface(final Map<String, Object> params) {
        _publicIp = (String) params.get("eth0ip");
        s_logger.debug("Configuring public interface with IP {}", _publicIp);
        if (_publicIp != null) {
            params.put("public.network.device", "eth0");
        }
    }

    private void configurePrivateInterface(final Map<String, Object> params) {
        _eth1ip = (String) params.get("eth1ip");
        _eth1mask = (String) params.get("eth1mask");
        s_logger.debug("Configuring private interface with IP {} and mask {}", _eth1ip, _eth1mask);
        if (_eth1ip != null) { // can only happen inside service vm
            params.put("private.network.device", "eth1");
        } else {
            s_logger.warn("eth1ip parameter has not been configured, assuming that we are not inside a system vm");
        }
    }

    protected void configureStorageLayerClass(final Map<String, Object> params) throws ConfigurationException {
        String value;
        if (_storage == null) {
            value = (String) params.get(StorageLayer.ClassConfigKey);
            if (value == null) {
                value = "com.cloud.storage.JavaStorageLayer";
            }

            try {
                final Class<?> clazz = Class.forName(value);
                _storage = (StorageLayer) clazz.newInstance();
                _storage.configure("StorageLayer", params);
            } catch (final ClassNotFoundException e) {
                throw new ConfigurationException("Unable to find class " + value);
            } catch (final InstantiationException e) {
                throw new ConfigurationException("Unable to find class " + value);
            } catch (final IllegalAccessException e) {
                throw new ConfigurationException("Unable to find class " + value);
            }
        }
    }

    private void addRouteToInternalIpOrCidr(final String localgw, final String eth1ip, final String eth1mask, final String destIpOrCidr) {
        if (!_inSystemVM) {
            return;
        }
        s_logger.debug("addRouteToInternalIp: localgw=" + localgw + ", eth1ip=" + eth1ip + ", eth1mask=" + eth1mask + ",destIp=" + destIpOrCidr);
        if (destIpOrCidr == null) {
            s_logger.debug("addRouteToInternalIp: destIp is null");
            return;
        }
        if (!NetUtils.isValidIp4(destIpOrCidr) && !NetUtils.isValidIp4Cidr(destIpOrCidr)) {
            s_logger.warn(" destIp is not a valid ip address or cidr destIp=" + destIpOrCidr);
            return;
        }
        boolean inSameSubnet = false;
        if (NetUtils.isValidIp4(destIpOrCidr)) {
            if (eth1ip != null && eth1mask != null) {
                inSameSubnet = NetUtils.sameSubnet(eth1ip, destIpOrCidr, eth1mask);
            } else {
                s_logger.warn("addRouteToInternalIp: unable to determine same subnet: _eth1ip=" + eth1ip + ", dest ip=" + destIpOrCidr + ", _eth1mask=" + eth1mask);
            }
        } else {
            inSameSubnet = NetUtils.isNetworkAWithinNetworkB(destIpOrCidr, NetUtils.ipAndNetMaskToCidr(eth1ip, eth1mask));
        }
        if (inSameSubnet) {
            s_logger.debug("addRouteToInternalIp: dest ip " + destIpOrCidr + " is in the same subnet as eth1 ip " + eth1ip);
            return;
        }
        Script command = new Script("/bin/bash", s_logger);
        command.add("-c");
        command.add("ip route delete " + destIpOrCidr);
        command.execute();
        command = new Script("/bin/bash", s_logger);
        command.add("-c");
        command.add("ip route add " + destIpOrCidr + " via " + localgw);
        final String result = command.execute();
        if (result != null) {
            s_logger.warn("Error in configuring route to internal ip err=" + result);
        } else {
            s_logger.debug("addRouteToInternalIp: added route to internal ip=" + destIpOrCidr + " via " + localgw);
        }
    }

    private void startAdditionalServices() {
        if (!_inSystemVM) {
            return;
        }
        Script command = new Script("/bin/bash", s_logger);
        command.add("-c");
        command.add("if [ -f /etc/init.d/ssh ]; then service ssh restart; else service sshd restart; fi ");
        String result = command.execute();
        if (result != null) {
            s_logger.warn("Error in starting sshd service err=" + result);
        }
        command = new Script("/bin/bash", s_logger);
        command.add("-c");
        command.add("iptables -I INPUT -i eth1 -p tcp -m state --state NEW -m tcp --dport 3922 -j ACCEPT");
        result = command.execute();
        if (result != null) {
            s_logger.warn("Error in opening up ssh port err=" + result);
        }
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    protected String getDefaultScriptsDir() {
        return "./scripts/storage/secondary";
    }

    @Override
    public void fillNetworkInformation(final StartupCommand cmd) {
        final String dummyMac = "00:06:0A:0B:0C:0D";
        final String dummyNetmask = "255.255.255.0";
        if (!_inSystemVM) {
            cmd.setPrivateIpAddress(_eth1ip);
            cmd.setPrivateMacAddress(dummyMac);
            cmd.setPrivateNetmask(dummyNetmask);
            cmd.setPublicIpAddress(_publicIp);
            cmd.setPublicMacAddress(dummyMac);
            cmd.setPublicNetmask(dummyNetmask);
            cmd.setName(_hostname);
        } else {
            super.fillNetworkInformation(cmd);
        }
    }

    @Override
    public void disconnected() {
    }

    private void configureSSL() {
        if (!_inSystemVM) {
            return;
        }
        s_logger.debug("Configuring SSL in SystemVM for public IP {} and hostname {}", _publicIp, _hostname);
        final Script command = new Script(_configSslScr);
        command.add("-i", _publicIp);
        command.add("-h", _hostname);
        final String result = command.execute();
        if (result != null) {
            s_logger.warn("Unable to configure httpd to use ssl");
        }
    }

    private void configureSSL(final String prvkeyPath, final String prvCertPath, final String certChainPath, final String rootCACert) {
        if (!_inSystemVM) {
            return;
        }
        final Script command = new Script(_configSslScr);
        command.add("-i", _publicIp);
        command.add("-h", _hostname);
        command.add("-k", prvkeyPath);
        command.add("-p", prvCertPath);
        if (certChainPath != null) {
            command.add("-t", certChainPath);
        }
        if (rootCACert != null) {
            command.add("-u", rootCACert);
        }
        final String result = command.execute();
        if (result != null) {
            s_logger.warn("Unable to configure httpd to use ssl");
        }
    }

    private String configureAuth(final String user, final String passwd) {
        final Script command = new Script(_configAuthScr);
        command.add(user);
        command.add(passwd);
        final String result = command.execute();
        if (result != null) {
            s_logger.warn("Unable to configure httpd to use auth");
        }
        return result;
    }

    private String configureIpFirewall(final List<String> ipList, final boolean isAppend) {
        final Script command = new Script(_configIpFirewallScr);
        command.add(String.valueOf(isAppend));
        for (final String ip : ipList) {
            command.add(ip);
        }

        final String result = command.execute();
        if (result != null) {
            s_logger.warn("Unable to configure firewall for command : " + command);
        }
        return result;
    }

    /**
     * Mount remote device named on local file system on subfolder of _parent
     * field.
     * <p>
     * <p>
     * Supported schemes are "nfs" and "cifs".
     * <p>
     * <p>
     * CIFS parameters are documented with mount.cifs at
     * http://linux.die.net/man/8/mount.cifs
     * For simplicity, when a URI is used to specify a CIFS share,
     * options such as domain,user,password are passed as query parameters.
     *
     * @param uri crresponding to the remote device. Will throw for unsupported
     *            scheme.
     * @return name of folder in _parent that device was mounted.
     * @throws UnknownHostException
     */
    protected String mountUri(final URI uri) throws UnknownHostException {
        final String uriHostIp = getUriHostIp(uri);
        final String nfsPath = uriHostIp + ":" + uri.getPath();

        // Single means of calculating mount directory regardless of scheme
        final String dir = UUID.nameUUIDFromBytes(nfsPath.getBytes()).toString();
        final String localRootPath = _parent + "/" + dir;

        // remote device syntax varies by scheme.
        final String remoteDevice;
        if (uri.getScheme().equals("cifs")) {
            remoteDevice = "//" + uriHostIp + uri.getPath();
            s_logger.debug("Mounting device with cifs-style path of " + remoteDevice);
        } else {
            remoteDevice = nfsPath;
            s_logger.debug("Mounting device with nfs-style path of " + remoteDevice);
        }

        mount(localRootPath, remoteDevice, uri);

        return dir;
    }

    protected void umount(final String localRootPath, final URI uri) {
        ensureLocalRootPathExists(localRootPath, uri);

        if (!mountExists(localRootPath, uri)) {
            return;
        }

        final Script command = new Script(!_inSystemVM, "mount", _timeout, s_logger);
        command.add(localRootPath);
        final String result = command.execute();
        if (result != null) {
            // Fedora Core 12 errors out with any -o option executed from java
            final String errMsg = "Unable to umount " + localRootPath + " due to " + result;
            s_logger.error(errMsg);
            final File file = new File(localRootPath);
            if (file.exists()) {
                file.delete();
            }
            throw new CloudRuntimeException(errMsg);
        }
        s_logger.debug("Successfully umounted " + localRootPath);
    }

    protected void ensureLocalRootPathExists(final String localRootPath, final URI uri) {
        s_logger.debug("making available " + localRootPath + " on " + uri.toString());
        final File file = new File(localRootPath);
        s_logger.debug("local folder for mount will be " + file.getPath());
        if (!file.exists()) {
            s_logger.debug("create mount point: " + file.getPath());
            _storage.mkdir(file.getPath());

            // Need to check after mkdir to allow O/S to complete operation
            if (!file.exists()) {
                final String errMsg = "Unable to create local folder for: " + localRootPath + " in order to mount " + uri.toString();
                s_logger.error(errMsg);
                throw new CloudRuntimeException(errMsg);
            }
        }
    }

    protected boolean mountExists(final String localRootPath, final URI uri) {
        Script script = null;
        script = new Script(!_inSystemVM, "mount", _timeout, s_logger);

        final List<String> res = new ArrayList<>();
        final DownloadManagerImpl.ZfsPathParser parser = new DownloadManagerImpl.ZfsPathParser(localRootPath);
        script.execute(parser);
        res.addAll(parser.getPaths());
        for (final String s : res) {
            if (s.contains(localRootPath)) {
                s_logger.debug("Some device already mounted at " + localRootPath + ", no need to mount " + uri.toString());
                return true;
            }
        }
        return false;
    }

    protected void mount(final String localRootPath, final String remoteDevice, final URI uri) {
        s_logger.debug("mount " + uri.toString() + " on " + localRootPath);
        ensureLocalRootPathExists(localRootPath, uri);

        if (mountExists(localRootPath, uri)) {
            return;
        }

        attemptMount(localRootPath, remoteDevice, uri);

        // XXX: Adding the check for creation of snapshots dir here. Might have
        // to move it somewhere more logical later.
        checkForSnapshotsDir(localRootPath);
        checkForVolumesDir(localRootPath);
    }

    protected void attemptMount(final String localRootPath, final String remoteDevice, final URI uri) {
        final String result;
        s_logger.debug("Make cmdline call to mount " + remoteDevice + " at " + localRootPath + " based on uri " + uri);
        final Script command = new Script(!_inSystemVM, "mount", _timeout, s_logger);

        final String scheme = uri.getScheme().toLowerCase();
        command.add("-t", scheme);

        if (scheme.equals("nfs")) {
            if ("Mac OS X".equalsIgnoreCase(System.getProperty("os.name"))) {
                // See http://wiki.qnap.com/wiki/Mounting_an_NFS_share_from_OS_X
                command.add("-o", "resvport");
            }
            if (_inSystemVM) {
                command.add("-o", "soft,timeo=133,retrans=2147483647,tcp,acdirmax=0,acdirmin=0");
            }
        } else if (scheme.equals("cifs")) {
            final String extraOpts = parseCifsMountOptions(uri);

            // nfs acdirmax / acdirmin correspoonds to CIFS actimeo (see
            // http://linux.die.net/man/8/mount.cifs)
            // no equivalent to nfs timeo, retrans or tcp in CIFS
            // todo: allow security mode to be set.
            command.add("-o", extraOpts + "soft,actimeo=0");
        } else {
            final String errMsg = "Unsupported storage device scheme " + scheme + " in uri " + uri.toString();
            s_logger.error(errMsg);
            throw new CloudRuntimeException(errMsg);
        }

        command.add(remoteDevice);
        command.add(localRootPath);
        result = command.execute();
        if (result != null) {
            // Fedora Core 12 errors out with any -o option executed from java
            final String errMsg = "Unable to mount " + remoteDevice + " at " + localRootPath + " due to " + result;
            s_logger.error(errMsg);
            final File file = new File(localRootPath);
            if (file.exists()) {
                file.delete();
            }
            throw new CloudRuntimeException(errMsg);
        }
        s_logger.debug("Successfully mounted " + remoteDevice + " at " + localRootPath);
    }

    protected String parseCifsMountOptions(final URI uri) {
        final List<NameValuePair> args = URLEncodedUtils.parse(uri, "UTF-8");
        boolean foundUser = false;
        boolean foundPswd = false;
        final StringBuilder extraOpts = new StringBuilder();
        for (final NameValuePair nvp : args) {
            final String name = nvp.getName();
            if (name.equals("user")) {
                foundUser = true;
                s_logger.debug("foundUser is" + foundUser);
            } else if (name.equals("password")) {
                foundPswd = true;
                s_logger.debug("password is present in uri");
            }

            extraOpts.append(name + "=" + nvp.getValue() + ",");
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.error("extraOpts now " + extraOpts);
        }

        if (!foundUser || !foundPswd) {
            final String errMsg =
                    "Missing user and password from URI. Make sure they" + "are in the query string and separated by '&'.  E.g. "
                            + "cifs://example.com/some_share?user=foo&password=bar";
            s_logger.error(errMsg);
            throw new CloudRuntimeException(errMsg);
        }
        return extraOpts.toString();
    }

    protected String getUriHostIp(final URI uri) throws UnknownHostException {
        final String nfsHost = uri.getHost();
        final InetAddress nfsHostAddr = InetAddress.getByName(nfsHost);
        final String nfsHostIp = nfsHostAddr.getHostAddress();
        s_logger.info("Determined host " + nfsHost + " corresponds to IP " + nfsHostIp);
        return nfsHostIp;
    }

    protected boolean checkForSnapshotsDir(final String mountPoint) {
        final String snapshotsDirLocation = mountPoint + File.separator + "snapshots";
        return createDir("snapshots", snapshotsDirLocation, mountPoint);
    }

    protected boolean checkForVolumesDir(final String mountPoint) {
        final String volumesDirLocation = mountPoint + "/" + "volumes";
        return createDir("volumes", volumesDirLocation, mountPoint);
    }

    protected boolean createDir(final String dirName, final String dirLocation, final String mountPoint) {
        boolean dirExists = false;

        final File dir = new File(dirLocation);
        if (dir.exists()) {
            if (dir.isDirectory()) {
                s_logger.debug(dirName + " already exists on secondary storage, and is mounted at " + mountPoint);
                dirExists = true;
            } else {
                if (dir.delete() && _storage.mkdir(dirLocation)) {
                    dirExists = true;
                }
            }
        } else if (_storage.mkdir(dirLocation)) {
            dirExists = true;
        }

        if (dirExists) {
            s_logger.info(dirName + " directory created/exists on Secondary Storage.");
        } else {
            s_logger.info(dirName + " directory does not exist on Secondary Storage.");
        }

        return dirExists;
    }

    @Override
    public void setName(final String name) {
        // TODO Auto-generated method stub

    }

    @Override
    public Map<String, Object> getConfigParams() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setConfigParams(final Map<String, Object> params) {
        // TODO Auto-generated method stub

    }

    @Override
    public int getRunLevel() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void setRunLevel(final int level) {
        // TODO Auto-generated method stub

    }

    public UploadEntity createUploadEntity(String uuid, final String metadata, final long contentLength) {
        final TemplateOrVolumePostUploadCommand cmd = getTemplateOrVolumePostUploadCmd(metadata);
        UploadEntity uploadEntity = null;
        if (cmd == null) {
            final String errorMessage = "unable decode and deserialize metadata.";
            updateStateMapWithError(uuid, errorMessage);
            throw new InvalidParameterValueException(errorMessage);
        } else {
            uuid = cmd.getEntityUUID();
            if (isOneTimePostUrlUsed(cmd)) {
                uploadEntity = uploadEntityStateMap.get(uuid);
                final StringBuilder errorMessage = new StringBuilder("The one time post url is already used");
                if (uploadEntity != null) {
                    errorMessage.append(" and the upload is in ").append(uploadEntity.getUploadState()).append(" state.");
                }
                throw new InvalidParameterValueException(errorMessage.toString());
            }
            final int maxSizeInGB = Integer.parseInt(cmd.getMaxUploadSize());
            final int contentLengthInGB = getSizeInGB(contentLength);
            if (contentLengthInGB > maxSizeInGB) {
                final String errorMessage = "Maximum file upload size exceeded. Content Length received: " + contentLengthInGB + "GB. Maximum allowed size: " + maxSizeInGB + "GB.";
                updateStateMapWithError(uuid, errorMessage);
                throw new InvalidParameterValueException(errorMessage);
            }
            checkSecondaryStorageResourceLimit(cmd, contentLengthInGB);
            try {
                final String absolutePath = cmd.getAbsolutePath();
                uploadEntity = new UploadEntity(uuid, cmd.getEntityId(), UploadEntity.Status.IN_PROGRESS, cmd.getName(), absolutePath);
                uploadEntity.setMetaDataPopulated(true);
                uploadEntity.setResourceType(UploadEntity.ResourceType.valueOf(cmd.getType()));
                uploadEntity.setFormat(Storage.ImageFormat.valueOf(cmd.getImageFormat()));
                //relative path with out ssvm mount info.
                uploadEntity.setTemplatePath(absolutePath);
                final String dataStoreUrl = cmd.getDataTo();
                final String installPathPrefix = getRootDir(dataStoreUrl) + File.separator + absolutePath;
                uploadEntity.setInstallPathPrefix(installPathPrefix);
                uploadEntity.setChksum(cmd.getChecksum());
                uploadEntity.setMaxSizeInGB(maxSizeInGB);
                uploadEntity.setDescription(cmd.getDescription());
                uploadEntity.setContentLength(contentLength);
                // create a install dir
                if (!_storage.exists(installPathPrefix)) {
                    _storage.mkdir(installPathPrefix);
                }
                uploadEntityStateMap.put(uuid, uploadEntity);
            } catch (final Exception e) {
                //upload entity will be null incase an exception occurs and the handler will not proceed.
                s_logger.error("exception occurred while creating upload entity ", e);
                updateStateMapWithError(uuid, e.getMessage());
            }
        }
        return uploadEntity;
    }

    private synchronized void checkSecondaryStorageResourceLimit(final TemplateOrVolumePostUploadCommand cmd, final int contentLengthInGB) {
        final String rootDir = getRootDir(cmd.getDataTo()) + File.separator;
        final long accountId = cmd.getAccountId();

        final long accountTemplateDirSize = 0;
        final File accountTemplateDir = new File(rootDir + getTemplatePathForAccount(accountId));
        if (accountTemplateDir.exists()) {
            FileUtils.sizeOfDirectory(accountTemplateDir);
        }
        long accountVolumeDirSize = 0;
        final File accountVolumeDir = new File(rootDir + getVolumePathForAccount(accountId));
        if (accountVolumeDir.exists()) {
            accountVolumeDirSize = FileUtils.sizeOfDirectory(accountVolumeDir);
        }
        long accountSnapshotDirSize = 0;
        final File accountSnapshotDir = new File(rootDir + getSnapshotPathForAccount(accountId));
        if (accountSnapshotDir.exists()) {
            accountSnapshotDirSize = FileUtils.sizeOfDirectory(accountSnapshotDir);
        }
        s_logger.debug("accountTemplateDirSize: " + accountTemplateDirSize + " accountSnapshotDirSize: " + accountSnapshotDirSize + " accountVolumeDirSize: " +
                accountVolumeDirSize);

        final int accountDirSizeInGB = getSizeInGB(accountTemplateDirSize + accountSnapshotDirSize + accountVolumeDirSize);
        final int defaultMaxAccountSecondaryStorageInGB = Integer.parseInt(cmd.getDefaultMaxAccountSecondaryStorage());

        if (accountDirSizeInGB + contentLengthInGB > defaultMaxAccountSecondaryStorageInGB) {
            s_logger.error("accountDirSizeInGb: " + accountDirSizeInGB + " defaultMaxAccountSecondaryStorageInGB: " + defaultMaxAccountSecondaryStorageInGB + " contentLengthInGB:"
                    + contentLengthInGB);
            final String errorMessage = "Maximum number of resources of type secondary_storage for account has exceeded";
            updateStateMapWithError(cmd.getEntityUUID(), errorMessage);
            throw new InvalidParameterValueException(errorMessage);
        }
    }

    private String getVolumePathForAccount(final long accountId) {
        return TemplateConstants.DEFAULT_VOLUME_ROOT_DIR + "/" + accountId;
    }

    private String getTemplatePathForAccount(final long accountId) {
        return TemplateConstants.DEFAULT_TMPLT_ROOT_DIR + "/" + TemplateConstants.DEFAULT_TMPLT_FIRST_LEVEL_DIR + accountId;
    }

    private String getSnapshotPathForAccount(final long accountId) {
        return TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR + "/" + accountId;
    }

    private boolean isOneTimePostUrlUsed(final TemplateOrVolumePostUploadCommand cmd) {
        final String uuid = cmd.getEntityUUID();
        final String uploadPath = getRootDir(cmd.getDataTo()) + File.separator + cmd.getAbsolutePath();
        return uploadEntityStateMap.containsKey(uuid) || new File(uploadPath).exists();
    }

    public String postUpload(final String uuid, final String filename) {
        final UploadEntity uploadEntity = uploadEntityStateMap.get(uuid);
        final int installTimeoutPerGig = 180 * 60 * 1000;

        final String resourcePath = uploadEntity.getInstallPathPrefix();
        final String finalResourcePath = uploadEntity.getTmpltPath(); // template download
        final UploadEntity.ResourceType resourceType = uploadEntity.getResourceType();

        final String fileSavedTempLocation = uploadEntity.getInstallPathPrefix() + "/" + filename;

        final String uploadedFileExtension = FilenameUtils.getExtension(filename);
        String userSelectedFormat = uploadEntity.getFormat().toString();
        if (uploadedFileExtension.equals("zip") || uploadedFileExtension.equals("bz2") || uploadedFileExtension.equals("gz")) {
            userSelectedFormat += "." + uploadedFileExtension;
        }
        final String formatError = ImageStoreUtil.checkTemplateFormat(fileSavedTempLocation, userSelectedFormat);
        if (StringUtils.isNotBlank(formatError)) {
            final String errorString = "File type mismatch between uploaded file and selected format. Selected file format: " + userSelectedFormat + ". Received: " + formatError;
            s_logger.error(errorString);
            return errorString;
        }

        int imgSizeGigs = getSizeInGB(_storage.getSize(fileSavedTempLocation));
        final int maxSize = uploadEntity.getMaxSizeInGB();
        if (imgSizeGigs > maxSize) {
            final String errorMessage = "Maximum file upload size exceeded. Physical file size: " + imgSizeGigs + "GB. Maximum allowed size: " + maxSize + "GB.";
            s_logger.error(errorMessage);
            return errorMessage;
        }
        imgSizeGigs++; // add one just in case
        final long timeout = (long) imgSizeGigs * installTimeoutPerGig;
        final Script scr = new Script(getScriptLocation(resourceType), timeout, s_logger);
        scr.add("-s", Integer.toString(imgSizeGigs));
        scr.add("-S", Long.toString(UploadEntity.s_maxTemplateSize));
        if (uploadEntity.getDescription() != null && uploadEntity.getDescription().length() > 1) {
            scr.add("-d", uploadEntity.getDescription());
        }
        final String checkSum = uploadEntity.getChksum();
        if (StringUtils.isNotBlank(checkSum)) {
            scr.add("-c", checkSum);
        }

        // add options common to ISO and template
        final String extension = uploadEntity.getFormat().getFileExtension();
        String templateName = "";
        if (extension.equals("iso")) {
            templateName = uploadEntity.getUuid().trim().replace(" ", "_");
        } else {
            try {
                templateName = UUID.nameUUIDFromBytes((uploadEntity.getFilename() + System.currentTimeMillis()).getBytes("UTF-8")).toString();
            } catch (final UnsupportedEncodingException e) {
                templateName = uploadEntity.getUuid().trim().replace(" ", "_");
            }
        }

        // run script to mv the temporary template file to the final template
        // file
        final String templateFilename = templateName + "." + extension;
        uploadEntity.setTemplatePath(finalResourcePath + "/" + templateFilename);
        scr.add("-n", templateFilename);

        scr.add("-t", resourcePath);
        scr.add("-f", fileSavedTempLocation); // this is the temporary
        // template file downloaded
        if (uploadEntity.getChksum() != null && uploadEntity.getChksum().length() > 1) {
            scr.add("-c", uploadEntity.getChksum());
        }
        scr.add("-u"); // cleanup
        final String result;
        result = scr.execute();

        if (result != null) {
            return result;
        }

        // Set permissions for the downloaded template
        final File downloadedTemplate = new File(resourcePath + "/" + templateFilename);
        _storage.setWorldReadableAndWriteable(downloadedTemplate);

        // Set permissions for template/volume.properties
        String propertiesFile = resourcePath;
        if (resourceType == UploadEntity.ResourceType.TEMPLATE) {
            propertiesFile += "/template.properties";
        } else {
            propertiesFile += "/volume.properties";
        }
        final File templateProperties = new File(propertiesFile);
        _storage.setWorldReadableAndWriteable(templateProperties);

        final TemplateLocation loc = new TemplateLocation(_storage, resourcePath);
        try {
            loc.create(uploadEntity.getEntityId(), true, uploadEntity.getFilename());
        } catch (final IOException e) {
            s_logger.warn("Something is wrong with template location " + resourcePath, e);
            loc.purge();
            return "Unable to upload due to " + e.getMessage();
        }

        final Map<String, Processor> processors = _dlMgr.getProcessors();
        for (final Processor processor : processors.values()) {
            FormatInfo info = null;
            try {
                info = processor.process(resourcePath, null, templateName);
            } catch (final InternalErrorException e) {
                s_logger.error("Template process exception ", e);
                return e.toString();
            }
            if (info != null) {
                loc.addFormat(info);
                uploadEntity.setVirtualSize(info.virtualSize);
                uploadEntity.setPhysicalSize(info.size);
                break;
            }
        }

        if (!loc.save()) {
            s_logger.warn("Cleaning up because we're unable to save the formats");
            loc.purge();
        }
        uploadEntity.setStatus(UploadEntity.Status.COMPLETED);
        uploadEntityStateMap.put(uploadEntity.getUuid(), uploadEntity);
        return null;
    }

    private int getSizeInGB(final long sizeInBytes) {
        return (int) Math.ceil(sizeInBytes * 1.0d / (1024 * 1024 * 1024));
    }

    private String getScriptLocation(final UploadEntity.ResourceType resourceType) {

        String scriptsDir = (String) _params.get("template.scripts.dir");
        if (scriptsDir == null) {
            scriptsDir = "scripts/storage/secondary";
        }
        String scriptname = null;
        if (resourceType == UploadEntity.ResourceType.VOLUME) {
            scriptname = "createvolume.sh";
        } else if (resourceType == UploadEntity.ResourceType.TEMPLATE) {
            scriptname = "createtmplt.sh";
        } else {
            throw new InvalidParameterValueException("cannot find script for resource type: " + resourceType);
        }
        return Script.findScript(scriptsDir, scriptname);
    }

    public void validatePostUploadRequest(final String signature, final String metadata, final String timeout, final String hostname, final long contentLength, final String
            uuid) throws
            InvalidParameterValueException {
        // check none of the params are empty
        if (StringUtils.isEmpty(signature) || StringUtils.isEmpty(metadata) || StringUtils.isEmpty(timeout)) {
            updateStateMapWithError(uuid, "signature, metadata and expires are compulsory fields.");
            throw new InvalidParameterValueException("signature, metadata and expires are compulsory fields.");
        }

        //check that contentLength exists and is greater than zero
        if (contentLength <= 0) {
            throw new InvalidParameterValueException("content length is not set in the request or has invalid value.");
        }

        //validate signature
        final String fullUrl = "https://" + hostname + "/upload/" + uuid;
        final String computedSignature = EncryptionUtil.generateSignature(metadata + fullUrl + timeout, getPostUploadPSK());
        final boolean isSignatureValid = computedSignature.equals(signature);
        if (!isSignatureValid) {
            updateStateMapWithError(uuid, "signature validation failed.");
            throw new InvalidParameterValueException("signature validation failed.");
        }

        //validate timeout
        final DateTime timeoutDateTime = DateTime.parse(timeout, ISODateTimeFormat.dateTime());
        if (timeoutDateTime.isBeforeNow()) {
            updateStateMapWithError(uuid, "request not valid anymore.");
            throw new InvalidParameterValueException("request not valid anymore.");
        }
    }

    public void updateStateMapWithError(final String uuid, final String errorMessage) {
        UploadEntity uploadEntity = null;
        if (uploadEntityStateMap.get(uuid) != null) {
            uploadEntity = uploadEntityStateMap.get(uuid);
        } else {
            uploadEntity = new UploadEntity();
        }
        uploadEntity.setStatus(UploadEntity.Status.ERROR);
        uploadEntity.setErrorMessage(errorMessage);
        uploadEntityStateMap.put(uuid, uploadEntity);
    }

    private String getPostUploadPSK() {
        if (_ssvmPSK == null) {
            try {
                _ssvmPSK = FileUtils.readFileToString(new File(POST_UPLOAD_KEY_LOCATION), "utf-8");
            } catch (final IOException e) {
                s_logger.debug("Error while reading SSVM PSK from location " + POST_UPLOAD_KEY_LOCATION, e);
            }
        }
        return _ssvmPSK;
    }

    private TemplateOrVolumePostUploadCommand getTemplateOrVolumePostUploadCmd(final String metadata) {
        TemplateOrVolumePostUploadCommand cmd = null;
        try {
            final Gson gson = new GsonBuilder().create();
            cmd = gson.fromJson(EncryptionUtil.decodeData(metadata, getPostUploadPSK()), TemplateOrVolumePostUploadCommand.class);
        } catch (final Exception ex) {
            s_logger.error("exception while decoding and deserialising metadata", ex);
        }
        return cmd;
    }
}
