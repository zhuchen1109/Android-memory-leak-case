package com.mat.example;

import android.os.AsyncTask;
import android.os.Bundle;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.TextView;

public class MainActivity extends Activity {

	private Bitmap bm;
	private TextView tv;

	private static int num = 0;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		tv = new TextView(this);
		tv.setText("TextView[Init]");
		setContentView(tv);

		if (num++ > 6) {
			return;
		}

		task();

		// 填充内存，加大内存占用，当出现内存泄露时，更方便的看出来
		bm = BitmapFactory.decodeResource(getResources(), R.raw.a123);
	}

	private void doSomeHeavyWork(final TextView tv, final String text) {
		tv.post(new Runnable() {
			@Override
			public void run() {
				tv.setText(text);
			}
		});
	}

	private void task() {
		new AsyncTask<TextView, Void, Void>() {

			@Override
			protected Void doInBackground(TextView... params) {
				try {
					TextView tv = params[0];
					// Thread.sleep(500);
					for (int i = 1; i <= 3; ++i) {
						doSomeHeavyWork(tv, "AsyncTask: " + i);
						Thread.sleep(1000);
					}
				} catch (Exception e) {
					// Log.e("xxxx", "e:" + e.toString());
				}
				return null;
			}

			protected void onPostExecute(Void result) {
				recreate();
			};

		}.execute(tv);
	}

}
