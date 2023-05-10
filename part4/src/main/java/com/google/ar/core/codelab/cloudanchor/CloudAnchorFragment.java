/*
 * Copyright 2019 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.codelab.cloudanchor;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Config.CloudAnchorMode;
import com.google.ar.core.Frame;
import com.google.ar.core.Future;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.codelab.cloudanchor.helpers.CameraPermissionHelper;
import com.google.ar.core.codelab.cloudanchor.helpers.FirebaseManager;
import com.google.ar.core.codelab.cloudanchor.helpers.ResolveDialogFragment;
import com.google.ar.core.codelab.cloudanchor.helpers.SnackbarHelper;
import com.google.ar.core.codelab.cloudanchor.helpers.StorageManager;
import com.google.ar.core.codelab.cloudanchor.helpers.TapHelper;
import com.google.ar.core.codelab.cloudanchor.helpers.TrackingStateHelper;
import com.google.ar.core.codelab.cloudanchor.rendering.BackgroundRenderer;
import com.google.ar.core.codelab.cloudanchor.rendering.ObjectRenderer;
import com.google.ar.core.codelab.cloudanchor.rendering.ObjectRenderer.BlendMode;
import com.google.ar.core.codelab.cloudanchor.rendering.PlaneRenderer;
import com.google.ar.core.codelab.cloudanchor.rendering.PointCloudRenderer;
import com.google.ar.core.codelab.cloudanchor.helpers.DisplayRotationHelper;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Main Fragment for the Cloud Anchors Codelab.
 *
 * <p>This is where the AR Session and the Cloud Anchors are managed.
 */
public class CloudAnchorFragment extends Fragment implements GLSurfaceView.Renderer {

  private static final String TAG = CloudAnchorFragment.class.getSimpleName();

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private TrackingStateHelper trackingStateHelper;
  private TapHelper tapHelper;
  private FirebaseManager firebaseManager;

  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final ObjectRenderer virtualObject = new ObjectRenderer();
  private final ObjectRenderer virtualObjectShadow = new ObjectRenderer();
  private final PlaneRenderer planeRenderer = new PlaneRenderer();
  private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] anchorMatrix = new float[16];
  private static final String SEARCHING_PLANE_MESSAGE = "Searching for surfaces...";
  private final float[] andyColor = {139.0f, 195.0f, 74.0f, 255.0f};

  @Nullable
  private Anchor currentAnchor = null;
  @Nullable
  private Future future = null;

  private Button resolveButton;

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);
    tapHelper = new TapHelper(context);
    trackingStateHelper = new TrackingStateHelper(requireActivity());
    firebaseManager = new FirebaseManager(context);
  }

  @Override
  public View onCreateView(
      LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    // Inflate from the Layout XML file.
    View rootView = inflater.inflate(R.layout.cloud_anchor_fragment, container, false);
    GLSurfaceView surfaceView = rootView.findViewById(R.id.surfaceView);
    this.surfaceView = surfaceView;
    displayRotationHelper = new DisplayRotationHelper(requireContext());
    surfaceView.setOnTouchListener(tapHelper);

    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);

    Button clearButton = rootView.findViewById(R.id.clear_button);
    clearButton.setOnClickListener(v -> onClearButtonPressed());

    resolveButton = rootView.findViewById(R.id.resolve_button);
    resolveButton.setOnClickListener(v -> onResolveButtonPressed());

    return rootView;
  }

  @Override
  public void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(requireActivity())) {
          CameraPermissionHelper.requestCameraPermission(requireActivity());
          return;
        }

        // Create the session.
        session = new Session(requireActivity());

        // Configure the session.
        Config config = new Config(session);
        config.setCloudAnchorMode(CloudAnchorMode.ENABLED);
        session.configure(config);

      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(requireActivity(), message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper
          .showError(requireActivity(), "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
    if (!CameraPermissionHelper.hasCameraPermission(requireActivity())) {
      Toast.makeText(requireActivity(), "Camera permission is needed to run this application",
          Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(requireActivity())) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(requireActivity());
      }
      requireActivity().finish();
    }
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(getContext());
      planeRenderer.createOnGlThread(getContext(), "models/trigrid.png");
      pointCloudRenderer.createOnGlThread(getContext());

      virtualObject.createOnGlThread(getContext(), "models/andy.obj", "models/andy.png");
      virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);

      virtualObjectShadow
          .createOnGlThread(getContext(), "models/andy_shadow.obj", "models/andy_shadow.png");
      virtualObjectShadow.setBlendMode(BlendMode.Shadow);
      virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);

    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      Frame frame = session.update();
      Camera camera = frame.getCamera();

      // Handle one tap per frame.
      handleTap(frame, camera);

      // If frame is ready, render camera preview image to the GL surface.
      backgroundRenderer.draw(frame);

      // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
      trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

      // If not tracking, don't draw 3D objects, show tracking failure reason instead.
      if (camera.getTrackingState() == TrackingState.PAUSED) {
        messageSnackbarHelper.showMessage(
            getActivity(), TrackingStateHelper.getTrackingFailureReasonString(camera));
        return;
      }

      // Get projection matrix.
      float[] projmtx = new float[16];
      camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

      // Get camera matrix and draw.
      float[] viewmtx = new float[16];
      camera.getViewMatrix(viewmtx, 0);

      // Compute lighting from average intensity of the image.
      // The first three components are color scaling factors.
      // The last one is the average pixel intensity in gamma space.
      final float[] colorCorrectionRgba = new float[4];
      frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

      // Visualize tracked points.
      // Use try-with-resources to automatically release the point cloud.
      try (PointCloud pointCloud = frame.acquirePointCloud()) {
        pointCloudRenderer.update(pointCloud);
        pointCloudRenderer.draw(viewmtx, projmtx);
      }

      // No tracking error at this point. If we didn't detect any plane, show searchingPlane message.
      if (!hasTrackingPlane()) {
        messageSnackbarHelper.showMessage(getActivity(), SEARCHING_PLANE_MESSAGE);
      }

      // Visualize planes.
      planeRenderer.drawPlanes(
          session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projmtx);

      if (currentAnchor != null && currentAnchor.getTrackingState() == TrackingState.TRACKING) {
        currentAnchor.getPose().toMatrix(anchorMatrix, 0);
        // Update and draw the model and its shadow.
        virtualObject.updateModelMatrix(anchorMatrix, 1f);
        virtualObjectShadow.updateModelMatrix(anchorMatrix, 1f);

        virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, andyColor);
        virtualObjectShadow.draw(viewmtx, projmtx, colorCorrectionRgba, andyColor);
      }
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private void handleTap(Frame frame, Camera camera) {
    if (currentAnchor != null) {
      return; // Do nothing if there was already an anchor.
    }

    MotionEvent tap = tapHelper.poll();
    if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
      for (HitResult hit : frame.hitTest(tap)) {
        // Check if any plane was hit, and if it was hit inside the plane polygon
        Trackable trackable = hit.getTrackable();
        // Creates an anchor if a plane or an oriented point was hit.
        if ((trackable instanceof Plane
            && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
            && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose()) > 0))
            || (trackable instanceof Point
            && ((Point) trackable).getOrientationMode()
            == OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
          // Hits are sorted by depth. Consider only closest hit on a plane or oriented point.

          // Adding an Anchor tells ARCore that it should track this position in
          // space. This anchor is created on the Plane to place the 3D model
          // in the correct position relative both to the world and to the plane.
          currentAnchor = hit.createAnchor();
          getActivity().runOnUiThread(() -> resolveButton.setEnabled(false));
          messageSnackbarHelper.showMessage(getActivity(), "Now hosting anchor...");
          future = session.hostCloudAnchorAsync(currentAnchor, 300, this::onHostComplete);
          break;
        }
      }
    }
  }

  /**
   * Checks if we detected at least one plane.
   */
  private boolean hasTrackingPlane() {
    for (Plane plane : session.getAllTrackables(Plane.class)) {
      if (plane.getTrackingState() == TrackingState.TRACKING) {
        return true;
      }
    }
    return false;
  }

  private void onClearButtonPressed() {
    // Clear the anchor from the scene.
    if (currentAnchor != null) {
      currentAnchor.detach();
      currentAnchor = null;
    }

    // Cancel any ongoing async operations.
    if (future != null) {
      future.cancel();
      future = null;
    }

    resolveButton.setEnabled(true);
  }

  private void onHostComplete(String cloudAnchorId, CloudAnchorState cloudState) {
    if (cloudState == CloudAnchorState.SUCCESS) {
      firebaseManager.nextShortCode(shortCode -> {
        if (shortCode != null) {
          firebaseManager.storeUsingShortCode(shortCode, cloudAnchorId);
          messageSnackbarHelper.showMessage(getActivity(), "Cloud Anchor Hosted. Short code: " + shortCode);
        } else {
          // Firebase could not provide a short code.
          messageSnackbarHelper.showMessage(getActivity(), "Cloud Anchor Hosted, but could not "
              + "get a short code from Firebase.");
        }
      });
    } else {
      messageSnackbarHelper.showMessage(getActivity(), "Error while hosting: " + cloudState.toString());
    }
  }

  private void onResolveButtonPressed() {
    ResolveDialogFragment dialog = ResolveDialogFragment.createWithOkListener(
        this::onShortCodeEntered);
    dialog.show(getActivity().getSupportFragmentManager(), "Resolve");
  }

  private void onShortCodeEntered(int shortCode) {
    firebaseManager.getCloudAnchorId(shortCode, cloudAnchorId -> {
      if (cloudAnchorId == null || cloudAnchorId.isEmpty()) {
        messageSnackbarHelper.showMessage(
            getActivity(),
            "A Cloud Anchor ID for the short code " + shortCode + " was not found.");
        return;
      }
      resolveButton.setEnabled(false);
      future = session.resolveCloudAnchorAsync(
          cloudAnchorId, (anchor, cloudState) -> onResolveComplete(anchor, cloudState, shortCode));
    });
  }

  private void onResolveComplete(Anchor anchor, CloudAnchorState cloudState, int shortCode) {
    if (cloudState == CloudAnchorState.SUCCESS) {
      messageSnackbarHelper.showMessage(getActivity(), "Cloud Anchor Resolved. Short code: " + shortCode);
      currentAnchor = anchor;
    } else {
      messageSnackbarHelper.showMessage(
          getActivity(),
          "Error while resolving anchor with short code "
              + shortCode
              + ". Error: "
              + cloudState.toString());
      resolveButton.setEnabled(true);
    }
  }
}
