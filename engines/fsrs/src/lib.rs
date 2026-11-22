mod bridge;

pub use bridge::jni_version;

#[cfg(test)]
mod tests {
    use fsrs::{FSRSItem, FSRSReview, FSRS};

    fn synthetic_items() -> Vec<FSRSItem> {
        let mut items = Vec::new();
        for card in 0..32u32 {
            let reviews = [
                (3, 0),
                (3, 2),
                (if card % 6 == 0 { 1 } else if card % 4 == 0 { 4 } else { 3 }, 3 + card % 7),
                (if card % 5 == 0 { 2 } else if card % 3 == 0 { 1 } else { 4 }, 1 + (card * 3) % 11),
                (if card % 7 == 0 { 1 } else { 3 }, 2 + (card * 5) % 17),
            ];
            for length in 2..=reviews.len() {
                items.push(FSRSItem {
                    reviews: reviews[..length]
                        .iter()
                        .map(|&(rating, delta_t)| FSRSReview { rating, delta_t })
                        .collect(),
                });
            }
        }
        items
    }

    #[test]
    fn evaluate_synthetic_training_set_matches_desktop() {
        let evaluation = FSRS::default()
            .evaluate(synthetic_items(), |_| true)
            .expect("evaluate");
        assert!((evaluation.log_loss - 0.4994423).abs() < 1e-6);
        assert!((evaluation.rmse_bins - 0.1804519).abs() < 1e-6);
    }
}
