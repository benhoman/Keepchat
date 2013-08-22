package com.sturmen.xposed.keepchat;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

public class Keepchat implements IXposedHookLoadPackage {
    /** The getVideoUri() hook unfortunately doesn't provide a context for displaying a toast or
     * calling the media scanner to show up the newly added media in the gallery. So after saving
     * the video, we store the file path into the private videoPath variable which we can in turn
     * access in the showVideo() hook (that gives us a context).
     */
	private String videoPath;

	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("com.snapchat.android"))
			return;
		else
			XposedBridge.log("Keepchat: Snapchat load detected.");

		/*
		 * getImageBitmap(Context) hook
		 * The ReceivedSnap class has a method to load a Bitmap in preparation for viewing.
		 * This method returns said bitmap back so the application can display it.
		 * We hook this method to intercept the result and write it to the SD card.
		 * We then use the handily-provided Context to display a toast notification of success.
		 */
		findAndHookMethod("com.snapchat.android.model.ReceivedSnap", lpparam.classLoader, "getImageBitmap", Context.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				File file = constructFileObject(param.thisObject, "jpg");
                // Check if the image was already saved.
                if (!file.exists()) {
					try {
                        Bitmap myImage = (Bitmap) param.getResult();
						XposedBridge.log("Bitmap loaded.");
						//open a new outputstream for writing
						FileOutputStream out = new FileOutputStream(file);
						//use built-in bitmap function to turn it into a jpeg and write it to the stream
						myImage.compress(Bitmap.CompressFormat.JPEG, 90, out);
						//flush the stream to make sure it's done
						out.flush();
						//close it
						out.close();
						//construct a log entry
						CharSequence text = "Saved to " + file.getCanonicalPath() + "!";
						XposedBridge.log(text.toString());
						//get the original context to use for the toast
						Context context = (Context) param.args[0];
						//construct a toast notification telling the user it was successful
						Toast toast = Toast.makeText(context, text, Toast.LENGTH_LONG);
						//display the toast for the user
						toast.show();
						XposedBridge.log("Image Toast displayed successfully.");
                        // Run a media scan, so it shows up in gallery
                        runMediaScan(context, file.getCanonicalPath());
					} catch (Exception e) {
						XposedBridge.log("Error occured while saving the file.");
						e.printStackTrace();
					}
                } else {
                    XposedBridge.log("Image already saved, doing nothing.");
                }
				//return the original image to the original caller so the app can continue
			}
		});
		/*
		 * getVideoUri() hook
		 * The ReceivedSnap class treats videos a little differently.
		 * Videos are not their own object, so they can't be passed around.
		 * The Android system basically provides a VideoView for viewing videos,
		 * which you just provide it the location of the video and it does the rest.
		 *
		 * Unsurprisingly, Snapchat makes use of this View.
		 * This method in the ReceivedSnap class gets the URI of the video
		 * in preparation for one of these VideoViews.
		 * We hook in, intercept the result (a String), then copy the bytes from
		 * that location to our SD directory. This results in a bit of a slowdown
		 * for the user, but luckily this takes place before they actually view it.
		 *
		 * The file path is stored in the mediaPath member for later use in the showVideo() hook.
		 */
		findAndHookMethod("com.snapchat.android.model.ReceivedSnap", lpparam.classLoader, "getVideoUri", new XC_MethodHook() {
            @Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				String videoUri = (String) param.getResult();
				XposedBridge.log("Video is at " + videoUri);
				File file = constructFileObject(param.thisObject, "mp4");
				videoPath = file.getCanonicalPath();
				try {
					//make a new input stream from the video URI
					FileInputStream in = new FileInputStream (new File(videoUri));
					//make a new output stream to write to
					FileOutputStream out = new FileOutputStream(file);
					//make a buffer we use for copying
					byte[] buf = new byte[1024];
					int len;
					//copy the file over using a while loop
					while ((len = in.read(buf)) > 0) {
						out.write(buf, 0, len);
					}
					//close the input stream
					in.close();
					//flush the output stream so we know it's finished
					out.flush();
					//and then close it
					out.close();
					//construct a log message
					CharSequence text = "Saved to " + file.getCanonicalPath() + " !";
					XposedBridge.log(text.toString());
				} catch (Exception e) {
					//if any exceptions are found, write to log
					XposedBridge.log("Error occured while saving the file.");
					e.printStackTrace();
				}
			}
		});
		/*
		 * showVideo() hook
		 * Because getVideoUri() does not handily provide a context,
		 * nor does its parent class (ReceivedSnap), we are unable to
		 * get the context necessary in order to display a notification.
		 * We "solve" this by cheating.
		 * This is an entirely separate hook that displays a Toast notification
		 * upon showing a video.
		 * It makes no checks whatsoever that the video saving was successful,
		 * it is merely for the benefit of the user to know that our hooks
		 * are working.
		 */
		findAndHookMethod("com.snapchat.android.ui.SnapView", lpparam.classLoader, "showVideo", Context.class, new XC_MethodHook() {
            @Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.args[0];
                // If the videoPath is not null, show a toast with the path and call the media scanner.
                // Otherwise, show an error toast message.
                CharSequence toastText;
                if (videoPath != null) {
                    // so video saved successfully.
                    toastText = "Saved video to " + videoPath;
                    runMediaScan(context, videoPath);
                } else {
                    toastText = "Video could not be saved!";
                }
                //construct the toast notification
                Toast toast = Toast.makeText(context, toastText, Toast.LENGTH_LONG);
                toast.show();
            }
		});
		/*
		 * wasScreenshotted() hook
		 * This method is called to see if the Snap was screenshotted.
		 * We hook it to always return false, meaning that it was not screenshotted.
		 */
		findAndHookMethod("com.snapchat.android.model.ReceivedSnap", lpparam.classLoader, "wasScreenshotted", new XC_MethodReplacement() {
			@Override
			protected Object replaceHookedMethod(MethodHookParam param)
					throws Throwable {
				XposedBridge.log("Not reporting screenshotted. :)");
				// the line
				return false;
			}
		});
	}
   /**
    * Tells the media scanner to scan the newly added image or video so that it shows up in the
    * gallery without a reboot. And shows a Toast message where the media was saved.
    *
    * context is simply the context.
    * filename is the file you want to be scanned.
    */
    private void runMediaScan(Context context, String filename) {
        try {
        	XposedBridge.log("MediaScanner running: " + filename);
            // Run MediaScanner on file, so it shows up in Gallery instantly
            MediaScannerConnection.scanFile(context,
                    new String[]{filename}, null,
                    new MediaScannerConnection.OnScanCompletedListener() {
                        public void onScanCompleted(String path, Uri uri) {
                            if (uri != null) {
                                XposedBridge.log("MediaScanner ran successfully: " + uri.toString());
                            } else {
                                XposedBridge.log("Unknown error occurred while trying to run MediaScanner");
                            }
                        }
                    });
        } catch (Exception e) {
            XposedBridge.log("Error occurred while trying to run MediaScanner");
            e.printStackTrace();
        }
    }

    /**
     * Return a File object to safe the image/video.
     * The filename will be in the format <sender>_yyyy-MM-dd_HH-mm-ss.<suffix>
     * and it resides in the <i>keepchat/</i> subfolder on the SD card. Along the way,
     * the <i>keepchat/</i> subfolder is created if not existent.
     *
     * @param snapObject The ReceivedSnap Object, which contains the necessary information, i.e.,
     *                   the senser's name and the snap's timestamp. It should be passed to the
     *                   method via 'param.thisObject' inside a hooked method.
     *
     * @param suffix The file suffix, either "jpg" or "mp4"
     */
    private File constructFileObject(Object snapObject, String suffix) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        //we construct a path for us to write to
        String root = Environment.getExternalStorageDirectory().toString();
        //and add our own directory
        File myDir = new File(root + "/keepchat");
        XposedBridge.log("Saving to directory " + myDir.toString());
        //we make the directory if it doesn't exist.
        if (myDir.mkdirs())
            XposedBridge.log("Directory " + myDir.toString() + " was created.");
        //construct the filename. It shall start with the sender's name...
        String sender = (String) callMethod(snapObject, "getSender");
        //...continue with the current date and time, lexicographically...
        SimpleDateFormat fnameDateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
          // ReceivedSnap extends the Snap class. getTimestamp() is a member of the Snap class,
          // so we cannot access it via XposedHelpers.callMethod() and have to use our own reflection for that
        Date timestamp = new Date((Long) callSuperMethod(snapObject, "getTimestamp"));
        //...and end in the suffix provided ("jpg" or "mp4")
        String fname = sender + "_" + (fnameDateFormat.format(timestamp)) + "." + suffix;
        XposedBridge.log("Saving with filename " + fname);
        //construct a File object
        return new File (myDir, fname);
    }
    /** {@code XposedHelpers.callMethod()} cannot call methods of the super class of an object, because it
     * uses {@code getDeclaredMethods()}. So we have to implement this little helper, which should work
     * similar to {@code }callMethod()}. Furthermore, the exceptions from getMethod() are passed on.
     * <p>
     * At the moment, only argument-free methods supported (only case needed here). After a discussion
     * with the Xposed author it looks as if the functionality to call super methods will be implemented
     * in {@code XposedHelpers.callMethod()} in a future release.
     *
     * @param obj Object whose method should be called
     * @param methodName String representing the name of the argument-free method to be called
     * @return The object that the method call returns
     * @see <a href="http://forum.xda-developers.com/showpost.php?p=42598280&postcount=1753">
     *     Discussion about calls to super methods in Xposed's XDA thread</a>
     */
    private Object callSuperMethod(Object obj, String methodName) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        return obj.getClass().getMethod(methodName).invoke(obj);
    }

}
