package dev.stillhome.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** A static landscape drawn by the GPU, without live blur or animation. */
final class BackdropView extends View {
    private final Palette palette;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader base;
    private Shader horizon;
    private Shader corner;

    BackdropView(Context context, Palette palette) {
        super(context);
        this.palette = palette;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        base = new LinearGradient(0, 0, width, height, palette.top, palette.bottom,
                Shader.TileMode.CLAMP);
        horizon = new RadialGradient(width * 0.52f, height * 0.78f, width * 0.65f,
                new int[] { palette.glow, palette.bottom & 0x00FFFFFF },
                new float[] { 0f, 1f }, Shader.TileMode.CLAMP);
        corner = new RadialGradient(width * 0.08f, height * 0.12f, width * 0.65f,
                new int[] { 0x523B6170, 0x003B6170 }, new float[] { 0f, 1f },
                Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (base == null) {
            return;
        }
        paint.setShader(base);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(horizon);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(corner);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
    }
}
