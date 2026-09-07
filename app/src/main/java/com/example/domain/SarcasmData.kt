package com.example.domain

val SARCASTIC_EXTENSION_L1 = listOf(
    "The limit has filed a complaint. You are requesting an extension.",
    "Just five minutes: the world's most renewable resource.",
    "Your quick check now has a director's cut.",
    "The timer kept its promise. Your move.",
    "An extension? The feed must be about to end. Any century now.",
    "The exit is right there. No subscription required.",
    "Your future self would like a word. Apparently you need five more minutes."
)

val SARCASTIC_EXTENSION_L2 = listOf(
    "A sequel to 'Just One More'. Somehow the plot is identical.",
    "Your deadline has become a suggestion with a nice font.",
    "The algorithm has promoted you to unpaid quality assurance.",
    "Another extension. Shall we send the limit a farewell card?",
    "You have renegotiated this contract more often than you have read it.",
    "Breaking news: the next post is also not the last post.",
    "Your thumb is putting in overtime. Who approved the budget?"
)

val SARCASTIC_EXTENSION_L3 = listOf(
    "This is no longer a quick check. It is a recurring appointment.",
    "The timer is now doing performance art about boundaries.",
    "Your five-minute plan has entered its third season.",
    "The feed has no bottom. We have established this experimentally.",
    "At this point, 'almost done' needs a fact-check label.",
    "The app is free. The afternoon apparently came bundled.",
    "We could close the app, but apparently we are collecting extensions."
)

val SARCASTIC_EXTENSION_L4 = listOf(
    "The limit is now a historical document. Please handle it with care.",
    "Congratulations. You have invented a timer with no consequences.",
    "Another encore. Even the end credits have end credits.",
    "This is a subscription paid entirely in afternoons.",
    "The algorithm sends its regards. It declined to send your time back.",
    "At this rate, the next reminder should arrive with a tenancy agreement.",
    "Your 'last one' has more sequels than a movie franchise."
)

val SARCASTIC_START_BUTTONS = listOf(
    "Begin the quick check", "Start the plot twist", "Let the feed audition",
    "Start my tiny detour", "Clock my curiosity", "Start. Hold me to it."
)

val SARCASTIC_EXTEND_BUTTONS = listOf(
    "Authorize the sequel", "Renegotiate reality", "Grant the encore",
    "Move my own goalposts", "Extend the plot", "One more. Allegedly."
)

val SARCASTIC_BYPASS = listOf(
    "Removing the limit because it worked. A fascinating troubleshooting strategy.",
    "No timer? Bold choice for a feed that has never once said 'that's enough'.",
    "You set a boundary and found the delete button. Very efficient.",
    "The algorithm would like to thank you for disabling the competition.",
    "Unlimited scrolling: all you can consume, billed in hours.",
    "This session wants diplomatic immunity from your own plans.",
    "The limit was your idea. I am merely the inconvenient witness.",
    "Proceed without a timer? Your calendar has not approved this expense."
)

val SARCASTIC_LONG_DURATION = listOf(
    "A quick look, now available in feature-length format.",
    "That is not a glance. That is a booking.",
    "The feed has accepted your generous donation of time.",
    "Planning a scroll or negotiating a lease?",
    "Your to-do list has been placed on hold. Excellent hold music, though.",
    "A timer this long deserves an intermission.",
    "The next post must be very important. Just like the previous ninety."
)

val SARCASTIC_QUOTA = listOf(
    "Today's budget is spent. The feed did not send a receipt.",
    "You set the quota. I brought the arithmetic.",
    "Daily limit reached. The algorithm is requesting a budget increase.",
    "Your attention allowance has left the chat.",
    "The time budget is empty. The feed, mysteriously, is not.",
    "A full day's quota. Gone in one very long 'quick check'."
)

val SARCASTIC_DISABLE = listOf(
    "Pausing the referee? Your call. The feed will manage its own time, apparently.",
    "Monitoring can take a break. Your plans still exist, for the record.",
    "The reminders are clocking out. You are now the timekeeper.",
    "Pause the nudges? Consider this my extremely brief handover note."
)

fun extensionRemark(extensionCount: Int): String = when (extensionCount.coerceAtLeast(0)) {
    0 -> SARCASTIC_EXTENSION_L1.random()
    1 -> SARCASTIC_EXTENSION_L2.random()
    2 -> SARCASTIC_EXTENSION_L3.random()
    else -> SARCASTIC_EXTENSION_L4.random()
}
