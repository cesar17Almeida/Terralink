// Series colours for the accuracy chart.
//
// These are NOT taken from the theme the way the timeline's tones are, and that is
// deliberate: theme roles are chosen for UI surfaces, and two of them side by side
// on a plot is a coincidence, not a palette. These four were picked as chart hues
// and checked against the app's light surface (#F6FBF3) for lightness band, chroma,
// colour-vision separation and contrast, so the two lines stay distinguishable for
// deuteranopic and protanopic readers -- who, on a soil-moisture chart, are exactly
// the audience most likely to be looking at a green-on-green screen outdoors.
//
// Identity never rests on colour alone here: the measured line is solid and the
// predicted one dashed, both are in the legend, and both are direct-labelled at
// their last point.
package com.astralink.terralink.ui.components.timeline

import androidx.compose.ui.graphics.Color

/** What the probe actually measured. Stays in the app's green family. */
val MeasuredColor = Color(0xFF1F7A4D)

/** What the LSTM said would happen. A hue no soil is, so it never reads as data. */
val PredictedColor = Color(0xFF8B5CF6)

/** Residuals diverge around zero: the model read the soil wetter than it was... */
val OverPredictColor = Color(0xFF1D6FB8)

/** ...or drier than it was. */
val UnderPredictColor = Color(0xFFB4531B)
