package com.ming.ebook.ebook.activity;

import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LayoutAnimationController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.ming.ebook.R;
import com.ming.ebook.base.BaseActivity;
import com.ming.ebook.bean.BookChapters;
import com.ming.ebook.bean.BookSource;
import com.ming.ebook.bean.ChaptersDetail;
import com.ming.ebook.constant.AppConstants;
import com.ming.ebook.constant.EBookUri;
import com.ming.ebook.dao.BookBeanDao;
import com.ming.ebook.dao.DbHelper;
import com.ming.ebook.dao.entity.BookBean;
import com.ming.ebook.decoration.DividerItemDecoration;
import com.ming.ebook.ebook.adapter.CategoriesListAdapter;
import com.ming.ebook.event.RefreshBookShelf;
import com.ming.ebook.utils.Model;
import com.ming.ebook.utils.MyLayoutAnimationHelper;
import com.ming.ebook.utils.PrintLog;
import com.ming.ebook.utils.UrlEncodeUtil;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;

public class ReadActivity extends BaseActivity implements CategoriesListAdapter.OnItemClickListener {
    private ProgressBar loadingBar;
    private ScrollView scrollViewRead;
    private TextView mTextView;
    private RecyclerView catalogueRecyclerView;
    private List<BookChapters.DataBean.ChaptersBean> categoriesList;
    private CategoriesListAdapter categoriesListAdapter;
    private int currentPosition = 0;
    private String book_id;
    private String book_name;
    private String book_cover;
    //Handler
    private final Handler mHandler = new ReadActivity.ReadHandler(this);

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //  If null, all callbacks and messages will be removed.
        mHandler.removeCallbacksAndMessages(null);
    }

    private static class ReadHandler extends Handler {
        private final WeakReference<ReadActivity> mActivity;

        public ReadHandler(ReadActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            if (mActivity.get() == null) {
                return;
            }

            if (msg != null) {
                mActivity.get().todo(msg);
            }
        }
    }

    /**
     * 处理msg逻辑方法
     *
     * @param msg
     */
    public void todo(Message msg) {
        switch (msg.what) {
            case AppConstants.BOOK_SOURCE_ID_HANDLER:
                List<BookSource.DataBean> dataBeenList = (List<BookSource.DataBean>) msg.obj;
                if (dataBeenList != null && dataBeenList.size() > 0) {
                    BookSource.DataBean sourceBean = dataBeenList.get(0);
                    String sourceId = sourceBean.get_id();
                    if (!TextUtils.isEmpty(sourceId)) {
                        //2.获取书籍章节
                        String bookChaptersUrl = EBookUri.BOOK_CHAPTERS + sourceId;
                        getBookSourceDataByUrl(bookChaptersUrl, AppConstants.TAG_BOOK_CHAPTERS);
                    }
                }
                break;
            case AppConstants.BOOK_CHAPTERS_HANDLER:
                List<BookChapters.DataBean.ChaptersBean> chaptersBeanList = (List<BookChapters.DataBean.ChaptersBean>) msg.obj;
                if (chaptersBeanList != null && chaptersBeanList.size() > 0) {
                    //3.1 添加章节对象
                    for (BookChapters.DataBean.ChaptersBean bean : chaptersBeanList) {
                        categoriesList.add(bean);
                    }
                    categoriesListAdapter.notifyDataSetChanged();

                    //3.2 获取章节详细内容
                    String link = chaptersBeanList.get(0).getLink();
                    String chaptersDetailUrl = EBookUri.BOOK_CHAPTERS_DETAIL + UrlEncodeUtil.encoderString(link);
                    getBookSourceDataByUrl(chaptersDetailUrl, AppConstants.TAG_BOOK_CHAPTERS_DETAIL);
                }

                break;

            case AppConstants.BOOK_CHAPTERS_DETAIL_HANDLER:
                //4.章节内容
                scrollViewRead.fullScroll(ScrollView.FOCUS_UP);
                ChaptersDetail.DataBean.ChapterBean chapterBean = (ChaptersDetail.DataBean.ChapterBean) msg.obj;
                PrintLog.d("ReadActivity:" + chapterBean.getCpContent());
                String content = chapterBean.getTitle() + "\n" + "\n" + chapterBean.getCpContent().trim();
                mTextView.setText(content);
                //显示内容
                scrollViewRead.setVisibility(View.VISIBLE);
                catalogueRecyclerView.setVisibility(View.GONE);
                loadingBar.setVisibility(View.GONE);
                break;
            default:
                break;

        }
    }

    @Override
    protected int getContentViewId() {
        immersiveStatusBar(R.color.transparent);
        return R.layout.activity_read;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        //ui
        loadingBar = (ProgressBar) findViewById(R.id.loading_bar);
        scrollViewRead = (ScrollView) findViewById(R.id.scrollView_read);
        mTextView = (TextView) findViewById(R.id.book_content);
        catalogueRecyclerView = (RecyclerView) findViewById(R.id.catalogue_recycler_view);
        catalogueRecyclerView.setHasFixedSize(true);
        LinearLayoutManager mLayoutManager = new LinearLayoutManager(this);
        catalogueRecyclerView.setLayoutManager(mLayoutManager);
        catalogueRecyclerView.setItemAnimator(new DefaultItemAnimator());
        catalogueRecyclerView.addItemDecoration(new DividerItemDecoration(this, LinearLayoutManager.VERTICAL));

        findViewById(R.id.catalogue_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCatalogueList();
            }
        });

        findViewById(R.id.next_bt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNextCatalogue();
            }
        });

        //data

        book_id = getIntent().getStringExtra("book_id");
        book_name = getIntent().getStringExtra("book_name");
        book_cover = getIntent().getStringExtra("book_cover");
        PrintLog.d("ReadActivity book_id:" + book_id);

        if (!TextUtils.isEmpty(book_id)) {
            //1.获取书籍源
            String bookSourceUrl = EBookUri.BOOK_SOURCE + book_id;
            getBookSourceDataByUrl(bookSourceUrl, AppConstants.TAG_BOOK_SOURCE_ID);
        }
        categoriesList = new ArrayList<>();
        categoriesListAdapter = new CategoriesListAdapter(categoriesList, this);
        catalogueRecyclerView.setAdapter(categoriesListAdapter);
        categoriesListAdapter.setOnItemClickListener(this);

    }

    /**
     * 下一章
     */
    private void showNextCatalogue() {
        if (currentPosition < (categoriesList.size() - 1)) {
            //显示圈
            scrollViewRead.setVisibility(View.GONE);
            catalogueRecyclerView.setVisibility(View.GONE);
            loadingBar.setVisibility(View.VISIBLE);

            currentPosition++;

            String link = categoriesList.get(currentPosition).getLink();
            String chaptersDetailUrl = EBookUri.BOOK_CHAPTERS_DETAIL + UrlEncodeUtil.encoderString(link);
            getBookSourceDataByUrl(chaptersDetailUrl, AppConstants.TAG_BOOK_CHAPTERS_DETAIL);
        } else {
            Toast.makeText(ReadActivity.this, "已经到末尾了......", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 目录
     */
    private void showCatalogueList() {
        if (catalogueRecyclerView.isShown()) {
            catalogueRecyclerView.setVisibility(View.GONE);
        } else {
            catalogueRecyclerView.setVisibility(View.VISIBLE);
            playLayoutAnimation(MyLayoutAnimationHelper.getAnimationSetFromLeft(), true);
        }
    }

    @Override
    public void onItemClick(View view, int position) {
        currentPosition = position;
        //显示圈
        scrollViewRead.setVisibility(View.GONE);
        catalogueRecyclerView.setVisibility(View.GONE);
        loadingBar.setVisibility(View.VISIBLE);

        String link = categoriesList.get(position).getLink();
        String chaptersDetailUrl = EBookUri.BOOK_CHAPTERS_DETAIL + UrlEncodeUtil.encoderString(link);
        getBookSourceDataByUrl(chaptersDetailUrl, AppConstants.TAG_BOOK_CHAPTERS_DETAIL);
    }

    /**
     * 播放RecyclerView动画
     *
     * @param animation
     * @param isReverse
     */
    public void playLayoutAnimation(Animation animation, boolean isReverse) {
        LayoutAnimationController controller = new LayoutAnimationController(animation);
        controller.setDelay(0.1f);
        controller.setOrder(isReverse ? LayoutAnimationController.ORDER_REVERSE : LayoutAnimationController.ORDER_NORMAL);

        catalogueRecyclerView.setLayoutAnimation(controller);
        catalogueRecyclerView.getAdapter().notifyDataSetChanged();
        catalogueRecyclerView.scheduleLayoutAnimation();
    }

    /**
     * 网络获取数据
     */
    private void getBookSourceDataByUrl(final String url, final int tag) {

        //网络访问
        Model.getInstance().getGloablThreadPool().execute(new Runnable() {

            @Override
            public void run() {
                OkHttpClient okHttpClient = new OkHttpClient();
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(url)
                        .build();
                Call call = okHttpClient.newCall(request);
                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        PrintLog.d("getBookSourceDataByUrl网络访问失败");
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) throws IOException {
                        int resCode = response.code();
                        if (resCode == 200) {
                            //解析json
                            String json = response.body().string();
                            PrintLog.d("getBookSourceDataByUrl json:" + json);
                            Gson gson = new Gson();
                            switch (tag) {
                                case AppConstants.TAG_BOOK_SOURCE_ID:

                                    BookSource bookSourceBean = gson.fromJson(json, BookSource.class);
                                    List<BookSource.DataBean> dataBeenList = bookSourceBean.getData();
                                    mHandler.obtainMessage(AppConstants.BOOK_SOURCE_ID_HANDLER, dataBeenList).sendToTarget();

                                    break;
                                case AppConstants.TAG_BOOK_CHAPTERS:

                                    BookChapters chaptersBean = gson.fromJson(json, BookChapters.class);
                                    List<BookChapters.DataBean.ChaptersBean> chaptersBeanList = chaptersBean.getData().getChapters();
                                    mHandler.obtainMessage(AppConstants.BOOK_CHAPTERS_HANDLER, chaptersBeanList).sendToTarget();

                                    break;
                                case AppConstants.TAG_BOOK_CHAPTERS_DETAIL:

                                    ChaptersDetail chaptersDetailBean = gson.fromJson(json, ChaptersDetail.class);
                                    ChaptersDetail.DataBean.ChapterBean chapterBean = chaptersDetailBean.getData().getChapter();
                                    mHandler.obtainMessage(AppConstants.BOOK_CHAPTERS_DETAIL_HANDLER, chapterBean).sendToTarget();

                                    break;

                                default:

                                    break;
                            }

                        }
                    }
                });
            }
        });

    }

    /**
     * 显示加入书架对话框
     */
    private void showJoinBookShelfDialog() {
        new AlertDialog.Builder(this)
                .setTitle("添加进书架")
                .setMessage("是否将本书添加进书架?")
                .setPositiveButton("加入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                        //添加操作
                        BookBean gridBook = new BookBean();
                        gridBook.setBook_id(book_id);
                        gridBook.setBook_cover(book_cover);
                        gridBook.setName(book_name);
                        gridBook.setReaded_chapter(categoriesList.get(currentPosition).getTitle());
                        DbHelper.getInstance().getmDaoSession().getBookBeanDao().insertOrReplace(gridBook);
                        //更新书架
                        RefreshBookShelf refreshBookShelf = new RefreshBookShelf();
                        refreshBookShelf.setRefreshBook(gridBook);
                        EventBus.getDefault().post(refreshBookShelf);
                        Toast.makeText(ReadActivity.this, "已添加进书架", Toast.LENGTH_SHORT).show();

                        dialog.dismiss();
                        finish();
                    }
                })
                .setNegativeButton("不了", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        finish();
                    }
                })
                .create()
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (KeyEvent.KEYCODE_BACK == keyCode) {
            List<BookBean> searchBookList = DbHelper.getInstance().getmDaoSession().getBookBeanDao().queryBuilder().where(BookBeanDao.Properties.Book_id.eq(book_id)).build().list();
            if (searchBookList.size() > 0) {
                finish();
            } else {
                showJoinBookShelfDialog();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);

    }

    @Override
    public void finish() {
        super.finish();

    }
}
