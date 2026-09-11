package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import com.codex.lockertest.ui.zip.ZipBrand;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

/** Presentation only: design-pixel typography and retained original brand/artwork. */
public final class KioskPolish {
    public static final int GREEN = Color.rgb(27, 181, 140);
    public static final int INK = Color.rgb(78, 99, 88);
    private static Typeface regular, semibold;
    private KioskPolish() { }

    public static synchronized Typeface typeface(Context context, boolean bold) {
        if (regular == null) regular = loadTypeface(context, "qg-regular.ttf", Typeface.NORMAL);
        if (semibold == null) semibold = loadTypeface(context, "qg-semibold.ttf", Typeface.BOLD);
        return bold ? semibold : regular;
    }

    private static Typeface loadTypeface(Context context, String asset, int style) {
        try {
            return Typeface.createFromAsset(context.getAssets(), asset);
        } catch (RuntimeException unavailable) {
            return Typeface.create("sans-serif", style);
        }
    }

    public static Drawable buttonSurface(Context context, int fill, int border, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},
                surface(context, Color.rgb(235,240,237), Color.rgb(218,228,222), radius));
        int pressed = Color.rgb(Color.red(fill) * 9 / 10, Color.green(fill) * 9 / 10,
                Color.blue(fill) * 9 / 10);
        states.addState(new int[]{android.R.attr.state_pressed}, surface(context, pressed, border, radius));
        states.addState(new int[0], surface(context, fill, border, radius));
        return states;
    }

    public static android.content.res.ColorStateList buttonInk(int enabled) {
        return new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]},
                new int[]{Color.rgb(156,173,163), enabled});
    }

    public static CharSequence twoLines(Context context, String first, String second,
            int firstSize, int secondSize, int secondColor) {
        SpannableString text = new SpannableString(first + "\n" + second);
        text.setSpan(new AbsoluteSizeSpan(ZipKioskShell.unit(context, firstSize)),
                0, first.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new AbsoluteSizeSpan(ZipKioskShell.unit(context, secondSize)),
                first.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(secondColor), first.length() + 1,
                text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    public static GradientDrawable surface(Context context, int fill, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(ZipKioskShell.unit(context, radius));
        if (border != Color.TRANSPARENT) shape.setStroke(Math.max(1,
                ZipKioskShell.unit(context, 1)), border);
        return shape;
    }

    public static void action(Button button, String title, String subtitle, int from, int to) {
        Context context = button.getContext();
        ZipKioskShell.scaleButton(button, 33);
        button.setTypeface(typeface(context, true));
        button.setIncludeFontPadding(false);
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setSingleLine(true);
        button.setHorizontallyScrolling(false);
        button.setText(title);
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(ZipKioskShell.unit(context, 107), 0,
                ZipKioskShell.unit(context, 13), 0);
        int kind = "掌纹录入".equals(title) ? 0 : "人脸识别".equals(title) ? 1 : 2;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, new MethodArtwork(context, kind, 0.55f));
        states.addState(new int[]{android.R.attr.state_pressed}, new MethodArtwork(context, kind, 0.82f));
        states.addState(new int[0], new MethodArtwork(context, kind, 1f));
        button.setBackground(states);
    }

    public static void addBackdrop(FrameLayout parent, boolean home) {
        parent.addView(new Artwork(parent.getContext(), home), new FrameLayout.LayoutParams(-1, -1));
    }

    public static Drawable floorFrame(Context context) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(248,252,250), Color.rgb(241,249,245)});
        d.setCornerRadius(ZipKioskShell.unit(context,8));
        d.setStroke(Math.max(1,ZipKioskShell.unit(context,1)), Color.rgb(129,200,177),
                ZipKioskShell.unit(context,4), ZipKioskShell.unit(context,3));
        return d;
    }

    public static View busyIndicator(Context context) {
        return new View(context) {
            final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                c.save();c.scale(getWidth()/160f,getHeight()/28f);
                long now=android.os.SystemClock.uptimeMillis();
                for(int i=0;i<5;i++) {
                    float wave=(float)(0.65+0.35*Math.sin(now/180.0-i*0.7));
                    p.setColor(Color.argb((int)(255*wave),53,198,247));
                    c.drawRoundRect(i*34,2,i*34+24,26,3,3,p);
                }
                c.restore();
                if(isAttachedToWindow() && getWindowVisibility()==VISIBLE) postInvalidateDelayed(80);
            }
        };
    }

    public static Drawable statusIcon(Context context, int status, boolean selected, int color) {
        Drawable icon = new Drawn() {
            @Override public void draw(Canvas c) {
                c.save(); c.translate(getBounds().left,getBounds().top);
                c.scale(getBounds().width()/18f,getBounds().height()/18f);
                p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.7f);
                p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
                if (selected && status==0) { Path check=new Path();check.moveTo(3,9);check.lineTo(7,13);check.lineTo(15,4);c.drawPath(check,p); }
                else if (status==0) c.drawCircle(9,9,6,p);
                else if (status>=4) { c.drawCircle(9,9,6,p);c.drawLine(5,5,13,13,p); }
                else { c.drawRoundRect(4,8,14,16,1,1,p);c.drawArc(6,1,12,12,180,180,false,p);c.drawLine(9,11,9,13,p); }
                c.restore();
            }
        };
        int size=ZipKioskShell.unit(context,15);icon.setBounds(0,0,size,size);return icon;
    }

    public static Drawable promptSurface() {
        return new Drawn() {
            @Override public void draw(Canvas c) {
                RectF r=new RectF(getBounds());p.setStyle(Paint.Style.FILL);
                p.setShader(new LinearGradient(r.left,r.top,r.right,r.top,
                        new int[]{Color.rgb(253,220,255),Color.rgb(156,239,252)},null,Shader.TileMode.CLAMP));
                c.drawRoundRect(r,36,36,p);p.setShader(null);
                p.setShader(new LinearGradient(0,r.top+25,0,r.top+r.height()*0.55f,
                        Color.TRANSPARENT,Color.WHITE,Shader.TileMode.CLAMP));
                c.drawRoundRect(r,36,36,p);p.setShader(null);
            }
        };
    }

    public static void decoratePrompt(FrameLayout card) {
        card.setClipChildren(false);card.setClipToPadding(false);
        final Drawable bell=resource(card.getContext(),"ui_detail_bell");
        View ornament=new View(card.getContext()) {
            @Override protected void onDraw(Canvas c) {
                c.save();c.scale(getWidth()/126f,getHeight()/143f);
                Path shape=new Path();shape.moveTo(83,1);shape.cubicTo(102,-2,105,18,91,22);
                shape.cubicTo(139,42,94,70,121,111);shape.cubicTo(143,149,78,137,64,128);
                shape.cubicTo(52,137,45,132,44,120);shape.cubicTo(18,111,-8,96,5,82);
                shape.cubicTo(29,70,34,61,39,42);shape.cubicTo(45,20,60,16,80,22);
                shape.cubicTo(73,17,73,6,83,1);shape.close();c.clipPath(shape);
                bell.setBounds(0,0,126,143);bell.draw(c);c.restore();
            }
        };
        ornament.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(ZipKioskShell.unit(card.getContext(),126),ZipKioskShell.unit(card.getContext(),143));
        lp.leftMargin=ZipKioskShell.unit(card.getContext(),28);lp.topMargin=-ZipKioskShell.unit(card.getContext(),51);
        card.addView(ornament,lp);
    }

    private static Drawable resource(Context context,String name) {
        int id=context.getResources().getIdentifier(name,"drawable",context.getPackageName());
        return context.getResources().getDrawable(id,context.getTheme()).mutate();
    }
    private abstract static class Drawn extends Drawable {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private static final class MethodArtwork extends Drawn {
        final Drawable original,fill;final int kind;final float alpha;
        MethodArtwork(Context context,int kind,float alpha){this.kind=kind;this.alpha=alpha;original=resource(context,"ui_detail_home");fill=resource(context,"ui_detail_method_"+kind);}
        @Override public void draw(Canvas c){
            c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/336f,getBounds().height()/100f);
            c.clipRect(0,0,336,100);c.save();c.translate(-900,-99-kind*120);original.setBounds(0,0,1280,800);original.draw(c);c.restore();
            fill.setBounds(107,20,323,81);fill.draw(c);
            if(alpha<1){p.setColor(Color.argb((int)(255*(1-alpha)),25,50,62));c.drawRoundRect(0,0,336,100,8,8,p);}c.restore();
        }
    }

    public static View icon(Context context, int kind) {
        return new View(context) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                canvas.save(); canvas.scale(getWidth() / 48f, getHeight() / 48f);
                p.setColor(Color.WHITE); p.setStrokeWidth(3); p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
                if (kind == 1) {
                    for (int x : new int[]{7, 34}) for (int y : new int[]{7, 34}) {
                        int dx = x == 7 ? 7 : -7, dy = y == 7 ? 7 : -7;
                        canvas.drawLine(x, y, x + dx, y, p); canvas.drawLine(x, y, x, y + dy, p);
                    }
                    canvas.drawCircle(17, 20, 1, p); canvas.drawCircle(28, 20, 1, p);
                    canvas.drawArc(14, 20, 32, 31, 20, 140, false, p);
                } else if (kind == 2) {
                    canvas.drawLine(5, 39, 43, 39, p);
                    canvas.drawRoundRect(12, 9, 36, 39, 3, 3, p);
                    canvas.drawLine(21, 8, 21, 41, p); canvas.drawCircle(27, 25, 1, p);
                } else {
                    Path path = new Path(); path.moveTo(13, 24); path.lineTo(13, 11);
                    path.cubicTo(13, 6, 19, 6, 19, 11); path.lineTo(19, 22);
                    path.lineTo(19, 7); path.cubicTo(19, 3, 25, 3, 25, 7); path.lineTo(25, 22);
                    path.lineTo(25, 10); path.cubicTo(25, 6, 31, 6, 31, 10); path.lineTo(31, 24);
                    path.lineTo(31, 15); path.cubicTo(31, 11, 37, 11, 37, 15);
                    path.lineTo(37, 29); path.cubicTo(37, 47, 20, 47, 13, 38);
                    path.lineTo(5, 29); path.cubicTo(2, 24, 7, 20, 13, 28);
                    canvas.drawPath(path, p);
                }
                canvas.restore();
            }
        };
    }

    private static final class Artwork extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Drawable original;
        private final boolean home;
        Artwork(Context context, boolean home) {
            super(context); this.home = home;
            int resource = context.getResources().getIdentifier(
                    "ui_detail_home", "drawable", context.getPackageName());
            original = resource == 0 ? null : context.getResources().getDrawable(resource, context.getTheme()).mutate();
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save(); canvas.scale(getWidth() / 1280f, getHeight() / 800f);
            if (!home) canvas.clipRect(0, 738, 1280, 800);
            if (original != null) { original.setBounds(0, 0, 1280, 800); original.draw(canvas); }
            if (home) {
                // Mask sample venue/count/status pixels, never show them as live server data.
                paint.setShader(new LinearGradient(42,100,400,255,Color.rgb(122,167,238),Color.rgb(129,194,243),Shader.TileMode.CLAMP));
                canvas.drawRoundRect(41,96,403,258,5,5,paint);paint.setShader(null);
                // Unsupported server-configured methods must not expose baked buttons.
                paint.setShader(new LinearGradient(900,99,1236,439,Color.rgb(104,171,238),Color.rgb(130,207,241),Shader.TileMode.CLAMP));
                canvas.drawRect(899,98,1237,440,paint);paint.setShader(null);
            }
            paint.setColor(Color.rgb(42,195,157));canvas.drawRoundRect(1075,749,1250,784,18,18,paint);
            paint.setTypeface(typeface(getContext(),true));paint.setTextSize(15);paint.setColor(Color.WHITE);
            canvas.drawText(ZipBrand.VERSION,1089,773,paint);
            canvas.restore();
        }
        private void crop(Canvas canvas, int sx, int sy, int sw, int sh,
                int x, int y, int width, int height) {
            if (original == null) return;
            canvas.save(); canvas.clipRect(x, y, x + width, y + height);
            canvas.translate(x, y); canvas.scale(width / (float) sw, height / (float) sh);
            canvas.translate(-sx, -sy); original.setBounds(0, 0, 1280, 800); original.draw(canvas);
            canvas.restore();
        }
    }
}
