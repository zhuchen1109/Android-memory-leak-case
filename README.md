Android MAT(Memory Analyzer Tool)
=========================

分析案例
----------------------
<b>Android非UI线程使用View.post()方法一处潜在的内存泄漏</b>

历史背景
----------------------
在开发中，使用AsyncTask + ProgressDialog 显示进度信息，但在AsyncTask停止，Activity finish 后该Activity的实例有时不会被gc，多次运行程序后，会存在多个activity，造成内存泄漏。
后来解决后发现此问题非常隐晦，很难发现，但造成的问题会很严重！
下面是一个演示此问题的DEMO

DEMO说明
----------------------
使用AsyncTask，在非UI线程里调用tv.post(...)做刷新操作，完整代码如下：
```java
public class Main extends Activity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        tv.setText("Init State");
        setContentView(tv);

        tv.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                showProgress(Main.this);
            }
        });
    }

    public void showProgress(final Activity activity) {
        new AsyncTask<Void, Void, Void>() {
            ProgressDialog progressDial;

            protected void onPreExecute() {
                progressDial  = new ProgressDialog(activity);
                progressDial.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                progressDial.show();
            };

            @Override
            protected Void doInBackground(Void... params) {
                doSomeHeavyWork(progressDial);
                return null;
            }

            protected void onPostExecute(Void result) {
                progressDial.dismiss();
            };

        }.execute();
    }

    void doSomeHeavyWork(ProgressDialog progress) {
        try {
            for (int i = 1; i <= 10; ++i) {
                progress.setProgress(i);
                Thread.sleep(1000);
            }
        } catch (Exception e) {
        }
    }
}
	
```

上述代码发生内存泄漏的地方在 doSomeHeavyWork() 的 progress.setProgress(i); 部分。
我们看一下setProgress()的实现，最终会调用ProgressBar 类的如下方法：

```java
    private synchronized void refreshProgress(int id, int progress, boolean fromUser) {
        if (mUiThreadId == Thread.currentThread().getId()) {
            doRefreshProgress(id, progress, fromUser, true);
        } else {
            RefreshProgressRunnable r;
            if (mRefreshProgressRunnable != null) {
                // Use cached RefreshProgressRunnable if available
                r = mRefreshProgressRunnable;
                // Uncache it
                mRefreshProgressRunnable = null;
                r.setup(id, progress, fromUser);
            } else {
                // Make a new one
                r = new RefreshProgressRunnable(id, progress, fromUser);
            }
            post(r);
        }
    }

    private class RefreshProgressRunnable implements Runnable {  
  
        private int mId;  
        private int mProgress;  
        private boolean mFromUser;  
  
        RefreshProgressRunnable(int id, int progress, boolean fromUser) {  
            mId = id;  
            mProgress = progress;  
            mFromUser = fromUser;  
        }  
  
        public void run() {  
            doRefreshProgress(mId, mProgress, mFromUser, true);  
            // Put ourselves back in the cache when we are done  
            mRefreshProgressRunnable = this;  
        }  
  
        public void setup(int id, int progress, boolean fromUser) {  
            mId = id;  
            mProgress = progress;  
            mFromUser = fromUser;  
        }  
    }
```

if 语句表明当调用的该方法的线程是UI线程时，则直接执行doRefreshProgress() 方法以刷新界面；否则，创建一个RefreshProgressRunnable，并通过调用View.post()方法将其插入到UI线程的消息队列中。 View.post()实现如下：

```java
    public boolean post(Runnable action) {
        Handler handler;
        AttachInfo attachInfo = mAttachInfo;
        if (attachInfo != null) {
            handler = attachInfo.mHandler;
        } else {
            // Assume that post will succeed later
            ViewRootImpl.getRunQueue().post(action);
            return true;
        }

        return handler.post(action);
    }
```

当ProgressDialog还没有attach到当前window时（ProgressDialog.show() 方法是异步执行的），mAttachInfo 值为 null，故而执行 else语句，再看一下getRunQueue()和其post() 方法：

```java
   static final ThreadLocal<RunQueue> sRunQueues = new ThreadLocal<RunQueue>();

   static RunQueue getRunQueue() {
        RunQueue rq = sRunQueues.get();
        if (rq != null) {
            return rq;
        }
        rq = new RunQueue();
        sRunQueues.set(rq);
        return rq;
    }
    ……
    static final class RunQueue {
        private final ArrayList<HandlerAction> mActions = new ArrayList<HandlerAction>();

        void post(Runnable action) {
            postDelayed(action, 0);
        }

        void postDelayed(Runnable action, long delayMillis) {
            HandlerAction handlerAction = new HandlerAction();
            handlerAction.action = action;
            handlerAction.delay = delayMillis;

            synchronized (mActions) {
                mActions.add(handlerAction);
            }
        }
         
        void executeActions(Handler handler) {
            synchronized (mActions) {
                final ArrayList<handleraction> actions = mActions;
                final int count = actions.size();

                for (int i = 0; i < count; i++) {
                    final HandlerAction handlerAction = actions.get(i);
                    handler.postDelayed(handlerAction.action, handlerAction.delay);
                }

                actions.clear();
            }
        }
        ……
    }
```

这样会把ProgressBar的RefreshProgressRunnable 插入到一个静态的ThreadLocal的RunQueue队列里，针对本文开头给出的例子，刷新进度的Runnable被插入到了AsyncTask 所在线程的RunQueue里； 那么插入的Runnable什么时候得到执行呢?
调用RunQueue.executeActions()方法只有一处，即在ViewRootImpl类的如下非静态方法中

```java
private void performTraversals() {
       ……
        if (mLayoutRequested && !mStopped) {
            // Execute enqueued actions on every layout in case a view that was detached
            // enqueued an action after being detached
            getRunQueue().executeActions(attachInfo.mHandler);
            ……
        }

    ……
}
```

该方法是在UI线程执行的（见ViewRootImpl.handleMessage()）， 故当UI线程执行到该performTraversals() 里的 getRunQueue() 时，得到的是UI线程中的RunQueue，这样AsyncTask 线程中的 RunQueue永远不会被执行到， 并且AsyncTask的是用线程池实现的，AsyncTask启动的线程会长期存在，造成如下引用关系：

AsyncTask线程 => 静态的ThreadLocal的RunQueue => Runnable => ProgressBar => Activity；

如此即使activity finish 了，确始终存在一个静态引用链引用这该activity，而 Activity一般又引用着很多资源，比如图片等，最终造成严重资源泄漏。


另外，上述问题不限与ProgressBar，凡是在非UI线程使用view.post()方法，如果view没有被attach，则均存在潜在的内存泄漏的问题！ 

针对本文给出的ProgressBar例子，一个简单fix方法实在 AsyncTask的doInbackground() 开始处sleep(500) 即可。 更为精准的方式可使用如下循环测试： 

```java
     View v = progressBar.getWindow().getDecorView();
     while（v.getWindowToken() == null) {
          Thread.sleep(10);
     }
```

上述ProgressBar例子，并不是总能再现内存泄漏的情况的（因为异步执行的不缺定性），下面再给出一个更容易再现类似问题的例子：



(https://raw.github.com/umano/AndroidSlidingUpPanelDemo/master/slidinguppanel.png)
