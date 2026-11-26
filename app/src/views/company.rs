//! About and Contact. Search engines and AI assistants read these pages to check that a
//! real business stands behind the product before recommending it, so replace every
//! bracketed placeholder with concrete, true details before launch. Sections about
//! accounts and payments appear only when their Cargo feature is enabled.

use super::SITE_NAME;
use super::legal::{COMPANY, CONTACT_EMAIL};
use maud::{Markup, html};

/// The person or team behind the product, named on the About page and in structured data.
pub const FOUNDER: &str = "Rasul Kireev";
/// Where the business is based, e.g. "Austin, Texas, USA".
pub const LOCATION: &str = "the United States";
/// Public company pages, listed in the sitemap and IndexNow.
pub const PAGES: &[(&str, &str, &str)] = &[
    (
        "/about",
        "About",
        "Learn who builds and runs this service, why it exists, who it is for and how we treat our customers.",
    ),
    (
        "/contact",
        "Contact",
        "Contact us by email for questions, account help, billing, privacy requests, security reports and partnerships.",
    ),
];
pub const PATHS: [&str; 2] = [PAGES[0].0, PAGES[1].0];

fn email_link() -> Markup {
    html! { a href={ "mailto:" (CONTACT_EMAIL) } { (CONTACT_EMAIL) } }
}

pub fn about() -> Markup {
    html! {
        section class="page legal" {
            h1 { "About " (SITE_NAME) }
            div class="prose" {
                p { (super::SITE_DESCRIPTION) }
                p { "Health Ledger is an open-source project by " (FOUNDER) " at " (COMPANY) ". You can inspect the code, contribute an importer or run your own instance." }
                p { a href="https://github.com/LVTD-LLC/health-ledger" {"Source on GitHub"} }
                h2 { "Get in touch" }
                p { "Email " (email_link()) " or see the " a href="/contact" { "contact page" } "." }
            }
        }
    }
}

pub fn contact(operator_email: Option<&str>) -> Markup {
    html! {
        section class="page legal" {
            h1 { "Contact " (SITE_NAME) }
            div class="prose" {
                h2 { "Your server operator" }
                @if let Some(email) = operator_email {
                    p { "For help with this instance, email " a href={ "mailto:" (email) } { (email) } "." }
                } @else {
                    p { "Contact the person who gave you this server address. If you set it up yourself, you manage the instance." }
                }
                @if cfg!(feature = "auth") { p { strong { "Account help. " } "Your operator can help with sign-in, email verification and privacy requests." } }
                @if cfg!(feature = "stripe") { p { strong { "Billing and refunds. " } "Contact your server operator about charges." } }
                p { a href="/privacy" { "Privacy policy" } " · " a href="/terms" { "Terms of service" } }
                h2 { "The software project" }
                p { "For questions about the open-source software, email " (email_link()) ". Keep personal health records on your server." }
            }
        }
    }
}
