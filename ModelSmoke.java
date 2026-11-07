import org.example.sparkfloor.*;
import ai.onnxruntime.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class ModelSmoke {
    public static void main(String[] args) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(args[1]));
        IntBuffer src = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int[] rgb = new int[512 * 512];
        src.get(rgb);
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions o = new OrtSession.SessionOptions()) {
            o.setIntraOpNumThreads(2);
            o.setInterOpNumThreads(1);
            try (OrtSession model = env.createSession(args[0], o);
                    OnnxTensor input =
                            OnnxTensor.createTensor(
                                    env,
                                    Segmentation.preprocess(rgb),
                                    new long[] {1, 3, 512, 512});
                    OrtSession.Result out =
                            model.run(
                                    Collections.singletonMap(
                                            model.getInputNames().iterator().next(), input))) {
                boolean[] floor =
                        Segmentation.floor(((float[][][][]) out.get(0).getValue())[0], 160, 120);
                byte[] mask = new byte[floor.length];
                for (int i = 0; i < mask.length; i++) mask[i] = (byte) (floor[i] ? 255 : 0);
                Files.write(Paths.get(args[2]), mask);
                Trapezoid t = Trapezoid.fit(floor, 160, 120);
                if (t == null)
                    throw new AssertionError("Expected floor trapezoid in embedded corridor");
                System.out.printf(
                        Locale.US,
                        "MODEL_AND_TRAPEZOID_PASS: lt=%.3f rt=%.3f lb=%.3f rb=%.3f top=%d bottom=%d area=%.2f%n",
                        t.lt,
                        t.rt,
                        t.lb,
                        t.rb,
                        t.top,
                        t.bottom,
                        t.area);
            }
        }
    }
}
