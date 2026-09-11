import android.content.Context;

import com.baidu.idl.main.facesdk.FaceAuth;
import com.baidu.idl.main.facesdk.FaceDetect;
import com.baidu.idl.main.facesdk.callback.Callback;
import com.baidu.idl.main.facesdk.model.BDFaceImageInstance;
import com.baidu.idl.main.facesdk.model.BDFaceInstance;
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon;
import com.baidu.liantian.ac.LH;
import com.baidu.vis.facecollect.license.AndroidLicenser;

/**
 * Compile-only SDK API contract for the pinned Baidu Face 8.5 AAR.
 * This class is intentionally never executed.
 */
public final class Baidu85ApiProbe {
    private static final String DETECT_MODEL =
            "face-sdk-models/detect/detect_rgb-customized-pa-192.model.float32-0.0.18.1";
    private static final String ALIGN_FAST_MODEL =
            "face-sdk-models/align/align_rgb-customized-pa-fast.model.float32-0.7.5.5";
    private static final String ALIGN_ACCURATE_MODEL =
            "face-sdk-models/align/align_rgb-customized-pa-80.model.float32-6.4.14.4";
    private static final String BLUR_MODEL =
            "face-sdk-models/blur/blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3";
    private static final String OCCLUSION_MODEL =
            "face-sdk-models/occlusion/occlusion-customized-pa-paddle.model.float32-2.0.7.3";
    private static final String BEST_IMAGE_MODEL =
            "face-sdk-models/best_image/best_image-mobilenet-pa-dcqe449_live_e51_relu_128.model.float32-1.0.3.1";

    private Baidu85ApiProbe() {
    }

    public static void verifyContract(Context context, byte[] nv21, Callback callback) {
        Class<?> faceAuth = FaceAuth.class;
        Class<?> liantian = LH.class;
        Class<?> androidLicenser = AndroidLicenser.class;

        BDFaceInstance instance = new BDFaceInstance();
        instance.creatInstance();
        FaceDetect tracker = new FaceDetect(instance);
        FaceDetect detector = new FaceDetect(instance);
        tracker.initModel(context, DETECT_MODEL, ALIGN_FAST_MODEL,
                BDFaceSDKCommon.DetectType.DETECT_VIS,
                BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_FAST, callback);
        detector.initModel(context, DETECT_MODEL, ALIGN_ACCURATE_MODEL,
                BDFaceSDKCommon.DetectType.DETECT_VIS,
                BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE, callback);
        detector.initQuality(context, BLUR_MODEL, OCCLUSION_MODEL, callback);
        detector.initBestImage(context, BEST_IMAGE_MODEL, callback);
        BDFaceImageInstance image = new BDFaceImageInstance(
                nv21, 480, 640,
                BDFaceSDKCommon.BDFaceImageType.BDFACE_IMAGE_TYPE_YUV_NV21,
                0, 1);
        try {
            tracker.track(BDFaceSDKCommon.DetectType.DETECT_VIS,
                    BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_FAST, image);
            detector.detect(BDFaceSDKCommon.DetectType.DETECT_VIS, image);
        } finally {
            image.destory();
        }

        if (faceAuth == null || liantian == null || androidLicenser == null) {
            throw new AssertionError("Pinned authorization symbols are unavailable");
        }
    }
}
