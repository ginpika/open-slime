package cc.ginpika.minis.coding.workspace;

import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.*;

/** Bounded binary transfer. No archive entry is ever extracted directly onto the host. */
public final class WorkspaceArchive {
    private WorkspaceArchive() {}
    public static final int TRANSFER_BYTES=16*1024*1024;
    public record Snapshot(Map<String,byte[]> files,Set<String> executable) {}
    public static String relative(String path){
        if(path==null || path.isBlank() || path.equals(".")){return ".";}
        if(path.startsWith("/") || path.indexOf('\\')>=0 || path.indexOf(':')>=0 || path.chars().anyMatch(c->c<32)){
            throw new IllegalArgumentException("请选择工作空间内的相对目录");
        }
        var parts=new ArrayList<String>();
        for(String part:path.split("/")){
            if(part.equals("..")){throw new IllegalArgumentException("不能跳出工作空间");}
            if(!part.isBlank() && !part.equals(".")){parts.add(part);}
        }
        String result=String.join("/",parts);
        if(CodingWorkspace.excluded(result)){throw new IllegalArgumentException("该目录/文件不在编程范围内");}
        return result.isEmpty()?".":result;
    }
    public static Snapshot unpack(byte[] bytes,CodingProps props) throws IOException {
        if(bytes.length>TRANSFER_BYTES){throw new IOException("工作空间传输超过 16 MiB");}
        Map<String,byte[]> files=new TreeMap<>();Set<String> modes=new HashSet<>();long total=0;int count=0;
        try(var tar=new TarArchiveInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))){
            TarArchiveEntry entry;
            while((entry=tar.getNextEntry())!=null){
                if(++count>props.getMaxFiles()*2){throw new IOException("归档条目过多");}
                String name=entry.getName();while(name.startsWith("./")){name=name.substring(2);}
                if(name.isEmpty() || name.equals(".")){continue;}
                // Validate even excluded entries so traversal cannot be hidden in an ignored path.
                if(name.startsWith("/") || name.indexOf('\\')>=0 || name.indexOf(':')>=0 || name.chars().anyMatch(c->c<32)
                        || Arrays.asList(name.split("/")).contains("..")){throw new IOException("归档含越界路径");}
                if(CodingWorkspace.excluded(name)){continue;}
                if(entry.isDirectory()){continue;}
                if(!entry.isFile() || entry.isSymbolicLink() || entry.isLink()){throw new IOException("项目包含不支持的链接/特殊文件："+name);}
                if(entry.getSize()<0 || entry.getSize()>props.getMaxWorkspaceBytes() || (total+=entry.getSize())>Math.min(props.getMaxWorkspaceBytes(),64L*1024*1024)){
                    throw new IOException("工作空间快照超过体积上限（最多 64 MiB）");
                }
                if(files.size()>=props.getMaxFiles() || files.containsKey(name)){throw new IOException("文件过多或重复路径");}
                byte[] content=tar.readNBytes((int)entry.getSize());if(content.length!=entry.getSize()){throw new IOException("归档不完整");}
                files.put(name,content);if((entry.getMode()&0111)!=0){modes.add(name);}
            }
        }
        return new Snapshot(files,modes);
    }
    public static byte[] pack(Map<String,byte[]> files,Set<String> executable) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var tar=new TarArchiveOutputStream(new GZIPOutputStream(bytes))){
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for(var item:files.entrySet()){
                String name=item.getKey();if(Path.of(name).isAbsolute() || Arrays.asList(name.split("/")).contains("..")){throw new IOException("非法归档路径");}
                var entry=new TarArchiveEntry(name);entry.setSize(item.getValue().length);entry.setMode(executable.contains(name)?0755:0644);
                tar.putArchiveEntry(entry);tar.write(item.getValue());tar.closeArchiveEntry();
            }
        }
        if(bytes.size()>TRANSFER_BYTES){throw new IOException("传输超过 16 MiB");}return bytes.toByteArray();
    }
}
