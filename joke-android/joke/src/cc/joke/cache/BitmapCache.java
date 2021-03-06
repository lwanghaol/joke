package cc.joke.cache;

import java.io.FileInputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Hashtable;
import java.util.Map;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import cc.joke.common.Constants;
import cc.joke.debug.Logger;
import cc.joke.http.BaseRequest;
import cc.joke.util.ImageUtil;
import cc.joke.util.ThreadPool;
import cc.joke.util.Util;
import cc.joke.view.ZoomableImageView;

public class BitmapCache
{
    private static final String TAG = "BitmapCache";

    private static BitmapCache cache;

    private Map<String, MySoftRef> hashRefs;

    private ReferenceQueue<Bitmap> queue;

    private BaseRequest baseRequest;

    private class MySoftRef extends SoftReference<Bitmap>
    {
        public String key;

        public MySoftRef(Bitmap bmp, ReferenceQueue<Bitmap> queue, String key)
        {
            super(bmp, queue);
            this.key = key;
        }
    }

    private BitmapCache()
    {
        hashRefs = new Hashtable<String, MySoftRef>();
        queue = new ReferenceQueue<Bitmap>();
        baseRequest = new BaseRequest();
    }

    public static BitmapCache getInstance()
    {
        if (cache == null)
        {
            cache = new BitmapCache();
        }
        return cache;
    }

    private void addCacheBitmap(String key, Bitmap bmp)
    {
        cleanCache();
        MySoftRef ref = new MySoftRef(bmp, queue, key);
        hashRefs.put(key, ref);
    }

    private void setBitmap(View view, Bitmap bm)
    {
        if (view == null || bm == null)
        {
            // DsLog.e(TAG, "view or bitmap is null!");
        }
        else if (view instanceof ImageView)
        {
            ImageView iv = (ImageView) view;
            iv.setImageBitmap(bm);
        }
        else if (view instanceof ZoomableImageView)
        {
            ZoomableImageView zv = (ZoomableImageView) view;
            zv.setImageBitmap(bm);
        }
        else if (view instanceof LinearLayout)
        {
            LinearLayout ll = (LinearLayout) view;
            ll.setBackgroundDrawable(new BitmapDrawable(bm));
        }
        else
        {
            Logger.e(TAG, view + "不是 ImageView|LinearLayout!");
        }
    }

    /**
     * //从网络下载图片
     * 
     * @param key
     * @param imageDownLoadCallback
     */
    public void getBitmap(final String key, final ImageDownLoadCallback imageDownLoadCallback,
            final ImageOption... option)
    {

        ThreadPool.add(new Runnable()
        {
            @Override
            public void run()
            {
                Bitmap bm = baseRequest.getBitmapByUrl(key, option);
                if (imageDownLoadCallback != null)
                {
                    imageDownLoadCallback.imageDownLoaded(bm);
                }
            }
        });
    }

    /**
     * 获取图片 (缓存=>本地资源=>SD卡=>网络=>null)
     * 
     * @param key
     * @param view
     * @param imageDownLoadCallback
     * @param option
     */
    public void getBitmap(final String key, final View view, final ImageDownLoadCallback imageDownLoadCallback,
            final ImageOption... option)
    {

        if (key == null)
        {
            return;
        }
        Bitmap bm = null;
        // 从队列中取
        if (hashRefs.containsKey(key))
        {
            MySoftRef ref = hashRefs.get(key);
            bm = ref.get();
            if (bm != null)
            {
                // DsLog.i(TAG, "从缓存中获取=>"+key);
                setBitmap(view, bm);
                if (imageDownLoadCallback != null)
                {
                    imageDownLoadCallback.imageDownLoaded(bm);
                }
                return;
            }
        }

        // 本地资源文件中取(drawable)
        Integer resId = null;
        try
        {
            resId = Integer.parseInt(key);
        }
        catch (Exception e)
        {
        }
        if (resId != null)
        {
            bm = ImageUtil.getBitmapById(resId);
            if (bm != null)
            {
                // DsLog.i(TAG, "从Drawable中获取=>"+key);
                addCacheBitmap(key, bm);
                setBitmap(view, bm);
                if (imageDownLoadCallback != null)
                {
                    imageDownLoadCallback.imageDownLoaded(bm);
                }
                return;
            }
        }

        // 从SD卡中取
        String imageName = Util.getFileNameByUrl(key);
        try
        {
            bm = BitmapFactory.decodeStream(new FileInputStream(Constants.DOWNLOAD_IMAGE_DIR + "/" + imageName));
        }
        catch (OutOfMemoryError e)
        {
            Logger.e("*_*", "悲剧，加载文件(" + Constants.DOWNLOAD_IMAGE_DIR + "/" + imageName + ")发生内存泄漏...");
        }
        catch (Exception e)
        {
        }
        if (bm != null)
        {
            // DsLog.i(TAG, "从SD中获取=>"+key);
            addCacheBitmap(key, bm);
            setBitmap(view, bm);
            if (imageDownLoadCallback != null)
            {
                imageDownLoadCallback.imageDownLoaded(bm);
            }
            return;
        }

        final Handler handler = new Handler()
        {
            public void handleMessage(Message message)
            {
                Bitmap bm = (Bitmap) message.obj;
                setBitmap(view, bm);
                if (imageDownLoadCallback != null)
                {
                    imageDownLoadCallback.imageDownLoaded(bm);
                }
            }
        };

        // 从网络下载
        ThreadPool.add(new Runnable()
        {
            @Override
            public void run()
            {
                Bitmap bm = baseRequest.getBitmapByUrl(key, option);
                if (bm != null)
                {
                    // DsLog.i(TAG, "从网络中获取=>"+key);
                    addCacheBitmap(key, bm);
                }
                handler.sendMessage(handler.obtainMessage(0, bm));
            }
        });
    }

    private void cleanCache()
    {
        MySoftRef ref = null;
        while ((ref = (MySoftRef) queue.poll()) != null)
        {
            hashRefs.remove(ref.key);
        }
    }

    public void clearCache()
    {
        cleanCache();
        hashRefs.clear();
        hashRefs = null;
        System.runFinalization();
    }
}
