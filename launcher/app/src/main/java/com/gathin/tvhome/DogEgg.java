package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The easter egg (Left five times on the first dock tile): the family dog runs in from the left, stops mid-screen
 * with something to say in a bubble, then runs off to the right. The picture is res/drawable/perro (a cut-out PNG
 * of the real dog) when the APK has one; until then a dog emoji stands in.
 */
final class DogEgg {
    private DogEgg() { }

    static void play(FrameLayout root, String message) {
        Context ctx = root.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        LinearLayout actor = new LinearLayout(ctx);
        actor.setOrientation(LinearLayout.VERTICAL);
        actor.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView bubble = new TextView(ctx);
        bubble.setText(message);
        bubble.setTextColor(0xFF111111);
        bubble.setTextSize(15f);
        bubble.setBackgroundResource(R.drawable.dog_bubble_bg);
        bubble.setPadding((int) (16 * density), (int) (10 * density), (int) (16 * density), (int) (10 * density));
        bubble.setMaxWidth((int) (280 * density));
        bubble.setAlpha(0f);
        actor.addView(bubble, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        View dog;
        int photo = ctx.getResources().getIdentifier("perro", "drawable", ctx.getPackageName());
        if (photo != 0) {
            ImageView image = new ImageView(ctx);
            Drawable d = ctx.getDrawable(photo);
            image.setImageDrawable(d);
            image.setAdjustViewBounds(true);
            image.setMaxHeight((int) (150 * density));
            dog = image;
        } else {
            TextView emoji = new TextView(ctx);
            emoji.setText("🐕");
            emoji.setTextSize(84f);
            dog = emoji;
        }
        LinearLayout.LayoutParams dogParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dogParams.topMargin = (int) (6 * density);
        actor.addView(dog, dogParams);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.topMargin = (int) (root.getHeight() * 0.42f);   // under the date, above the dock
        root.addView(actor, lp);

        actor.post(() -> {
            float width = root.getWidth();
            float start = -actor.getWidth();
            float middle = (width - actor.getWidth()) / 2f;
            // The emoji (and a photo taken facing left) looks left: mirror it while it runs right.
            dog.setScaleX(-1f);
            actor.setTranslationX(start);
            ValueAnimator hops = ValueAnimator.ofFloat(0f, (float) (Math.PI * 12));
            hops.setDuration(5200);
            hops.setInterpolator(new LinearInterpolator());
            hops.addUpdateListener(a -> dog.setTranslationY(
                    -Math.abs((float) Math.sin((float) a.getAnimatedValue())) * 18 * density));
            hops.start();
            actor.animate().translationX(middle).setDuration(1700).withEndAction(() -> {
                hops.pause();
                dog.setTranslationY(0f);
                // A wag while it talks.
                dog.animate().rotation(-8f).setDuration(160).withEndAction(() ->
                        dog.animate().rotation(8f).setDuration(160).withEndAction(() ->
                                dog.animate().rotation(0f).setDuration(160).start()).start()).start();
                bubble.animate().alpha(1f).setDuration(220).start();
                actor.postDelayed(() -> {
                    bubble.animate().alpha(0f).setDuration(200).start();
                    hops.resume();
                    actor.animate().translationX(width + actor.getWidth()).setDuration(1700)
                            .withEndAction(() -> {
                                hops.cancel();
                                root.removeView(actor);
                            }).start();
                }, 3200);
            }).start();
        });
    }
}
