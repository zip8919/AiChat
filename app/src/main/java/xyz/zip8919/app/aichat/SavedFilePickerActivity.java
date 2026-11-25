package xyz.zip8919.app.aichat;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 从「已保存文件」里挑一个当附件。
 *
 * <p>加号菜单原来是「文本文件 / 图片 / 拍照 / 图片链接」四条，全部落进系统文件
 * 选择器。但这里面有一类文件是本应用自己导出的（代码块、查看器截图、主题包），
 * 用户早就知道它在哪，却还要在选择器里从根目录一路点进去找。这一页直接列出来，
 * 点一下即添加。
 *
 * <p>只读，不做重命名和删除：那些操作在「存储空间 → 已保存文件」里，两处都能改
 * 会让「改完这页没刷新」变成常态。
 */
public class SavedFilePickerActivity extends ThemedActivity {

    private static final String TAG = "SavedFilePicker";

    /** 选中文件的绝对路径。调用方按扩展名决定走文本还是图片通道。 */
    public static final String EXTRA_PATH = "saved_file_path";

    private final List<File> files = new ArrayList<File>();
    private ListView list;
    private TextView empty;
    private FileAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved_file_picker);

        list = (ListView) findViewById(R.id.picker_list);
        empty = (TextView) findViewById(R.id.picker_empty);
        adapter = new FileAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= files.size()) return;
                File f = files.get(pos);
                LogUtil.i(TAG, "picked: %s (%d bytes)", f.getName(), f.length());
                Intent out = new Intent();
                out.putExtra(EXTRA_PATH, f.getAbsolutePath());
                setResult(RESULT_OK, out);
                finish();
            }
        });
        reload();
    }

    private File exportsDir() {
        String base = StorageManager.getInstance(getApplicationContext()).getBasePath();
        if (base == null) {
            // 存储还没初始化完：先指向内部目录，至少不 NPE
            return new File(getFilesDir(), "exports");
        }
        return new File(base, "exports");
    }

    private void reload() {
        files.clear();
        File[] all = exportsDir().listFiles();
        if (all != null) {
            for (int i = 0; i < all.length; i++) {
                if (all[i].isFile()) files.add(all[i]);
            }
        }
        // 最近改过的排前面：刚导出的那个通常就是这次要用的
        Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                long x = a.lastModified();
                long y = b.lastModified();
                if (x == y) return a.getName().compareToIgnoreCase(b.getName());
                return x > y ? -1 : 1;
            }
        });
        boolean none = files.isEmpty();
        // 空目录要把「怎么办」写出来，不能只留一片白
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
        LogUtil.d(TAG, "reload: %d files in %s", files.size(), exportsDir().getAbsolutePath());
    }

    private final class FileAdapter extends BaseAdapter {
        private final SimpleDateFormat fmt =
                new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

        public int getCount() {
            return files.size();
        }

        public Object getItem(int position) {
            return files.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(SavedFilePickerActivity.this)
                        .inflate(R.layout.item_storage_file, parent, false);
            }
            File f = files.get(position);
            String name = f.getName();
            ((TextView) row.findViewById(R.id.file_name)).setText(name);
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) {
                ext = name.substring(dot + 1).toUpperCase(Locale.US) + " · ";
            }
            ((TextView) row.findViewById(R.id.file_cat)).setText(
                    ext + fmt.format(new Date(f.lastModified())));
            ((TextView) row.findViewById(R.id.file_size)).setText(StorageReport.size(f.length()));
            ThemeRecolor.applyTo(row);
            FileIconBinder.bind((ImageView) row.findViewById(R.id.file_icon), f);
            row.setBackgroundColor(ThemeRecolor.listBg(row.getContext()));
            return row;
        }
    }
}
