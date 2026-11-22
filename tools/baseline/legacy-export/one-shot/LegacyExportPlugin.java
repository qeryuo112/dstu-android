package com.deepstudent.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

@CapacitorPlugin(name = "LegacyExport")
public class LegacyExportPlugin extends Plugin {
    private Uri destination;
    private OutputStream output;
    private MessageDigest digest;
    private long written;

    @PluginMethod
    public void create(PluginCall call) {
        if (destination != null) {
            call.reject("已有保存任务");
            return;
        }
        String fileName = call.getString("fileName");
        if (fileName == null || !fileName.endsWith(".zip") || fileName.contains("/")) {
            call.reject("非法归档文件名");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        startActivityForResult(call, intent, "documentCreated");
    }

    @ActivityCallback
    private void documentCreated(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            JSObject answer = new JSObject();
            answer.put("cancelled", true);
            call.resolve(answer);
            return;
        }
        try {
            destination = result.getData().getData();
            if (destination == null) throw new IllegalStateException("系统未返回文件 URI");
            output = getContext().getContentResolver().openOutputStream(destination, "w");
            if (output == null) throw new IllegalStateException("无法打开系统文件");
            digest = MessageDigest.getInstance("SHA-256");
            written = 0;
            JSObject answer = new JSObject();
            answer.put("cancelled", false);
            answer.put("uri", destination.toString());
            call.resolve(answer);
        } catch (Exception error) {
            cleanup();
            call.reject(error.getMessage(), error);
        }
    }

    @PluginMethod
    public synchronized void append(PluginCall call) {
        try {
            if (output == null) throw new IllegalStateException("没有活动保存任务");
            String encoded = call.getString("data");
            if (encoded == null || encoded.length() > 87384) throw new IllegalArgumentException("归档块超过 64 KiB");
            long offset = Long.parseLong(call.getString("offset", "-1"));
            if (offset != written) throw new IllegalArgumentException("归档块偏移不一致");
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            if (bytes.length == 0 || bytes.length > 65536) throw new IllegalArgumentException("非法归档块大小");
            output.write(bytes);
            digest.update(bytes);
            written += bytes.length;
            call.resolve();
        } catch (Exception error) {
            cleanup();
            call.reject(error.getMessage(), error);
        }
    }

    @PluginMethod
    public synchronized void finish(PluginCall call) {
        try {
            if (output == null) throw new IllegalStateException("没有活动保存任务");
            long expectedBytes = Long.parseLong(call.getString("bytes", "-1"));
            String expectedHash = call.getString("sha256", "");
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
            if (written != expectedBytes || !hash.toString().equals(expectedHash)) throw new IllegalArgumentException("归档大小或 SHA-256 不一致");
            output.flush();
            output.close();
            output = null;
            JSObject receipt = new JSObject();
            receipt.put("uri", destination.toString());
            receipt.put("bytes", Long.toString(written));
            receipt.put("sha256", hash.toString());
            destination = null;
            digest = null;
            written = 0;
            call.resolve(receipt);
        } catch (Exception error) {
            cleanup();
            call.reject(error.getMessage(), error);
        }
    }

    @PluginMethod
    public synchronized void abort(PluginCall call) {
        cleanup();
        call.resolve();
    }

    private void cleanup() {
        if (output != null) {
            try { output.close(); } catch (Exception error) { android.util.Log.e("LegacyExport", "close failed", error); }
        }
        if (destination != null) {
            try { DocumentsContract.deleteDocument(getContext().getContentResolver(), destination); }
            catch (Exception error) { android.util.Log.e("LegacyExport", "partial document cleanup failed", error); }
        }
        output = null;
        destination = null;
        digest = null;
        written = 0;
    }

    @Override
    protected void handleOnDestroy() {
        cleanup();
        super.handleOnDestroy();
    }
}
