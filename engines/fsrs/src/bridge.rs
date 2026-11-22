use std::panic::{catch_unwind, AssertUnwindSafe};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

use fsrs::{
    compute_parameters, check_and_fill_parameters, CombinedProgressState, ComputeParametersInput,
    FSRSItem, FSRSReview, FSRSError, MemoryState, FSRS,
};
use jni::objects::{JClass, JFloatArray, JIntArray, JLongArray};
use jni::sys::{jboolean, jfloat, jfloatArray, jint, jlong, jstring, JNI_TRUE};
use jni::JNIEnv;

type SharedProgress = Arc<Mutex<CombinedProgressState>>;

struct CancelRegistry {
    next: u64,
    tokens: HashMap<u64, SharedProgress>,
}

impl CancelRegistry {
    fn new() -> Self {
        Self {
            next: 1,
            tokens: HashMap::new(),
        }
    }
}

fn registry() -> &'static Mutex<CancelRegistry> {
    static REGISTRY: OnceLock<Mutex<CancelRegistry>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(CancelRegistry::new()))
}

fn read_floats(env: &mut JNIEnv<'_>, array: &JFloatArray) -> Result<Vec<f32>, String> {
    let len = env.get_array_length(array).map_err(|error| error.to_string())?;
    let mut values = vec![0.0f32; len.max(0) as usize];
    if len > 0 {
        env.get_float_array_region(array, 0, &mut values)
            .map_err(|error| error.to_string())?;
    }
    Ok(values)
}

fn read_ints(env: &mut JNIEnv<'_>, array: &JIntArray) -> Result<Vec<i32>, String> {
    let len = env.get_array_length(array).map_err(|error| error.to_string())?;
    let mut values = vec![0i32; len.max(0) as usize];
    if len > 0 {
        env.get_int_array_region(array, 0, &mut values)
            .map_err(|error| error.to_string())?;
    }
    Ok(values)
}

fn read_longs(env: &mut JNIEnv<'_>, array: &JLongArray) -> Result<Vec<i64>, String> {
    let len = env.get_array_length(array).map_err(|error| error.to_string())?;
    let mut values = vec![0i64; len.max(0) as usize];
    if len > 0 {
        env.get_long_array_region(array, 0, &mut values)
            .map_err(|error| error.to_string())?;
    }
    Ok(values)
}

fn floats(env: &mut JNIEnv<'_>, values: &[f32]) -> Result<jfloatArray, String> {
    let array = env
        .new_float_array(values.len() as i32)
        .map_err(|error| error.to_string())?;
    if !values.is_empty() {
        env.set_float_array_region(&array, 0, values)
            .map_err(|error| error.to_string())?;
    }
    Ok(array.into_raw())
}

fn strings(env: &mut JNIEnv<'_>, text: &str) -> Result<jstring, String> {
    let value = env
        .new_string(text)
        .map_err(|error| error.to_string())?;
    Ok(value.into_raw())
}

fn throw(env: &mut JNIEnv<'_>, class: &str, message: &str) {
    let _ = env.throw_new(class, message);
}

fn map_error(error: FSRSError) -> (&'static str, &'static str) {
    match error {
        FSRSError::NotEnoughData => ("java/lang/IllegalArgumentException", "NotEnoughData"),
        FSRSError::Interrupted => ("java/lang/IllegalStateException", "Interrupted"),
        FSRSError::InvalidParameters => ("java/lang/IllegalArgumentException", "InvalidParameters"),
        FSRSError::OptimalNotFound => ("java/lang/IllegalStateException", "OptimalNotFound"),
        FSRSError::InvalidInput => ("java/lang/IllegalArgumentException", "InvalidInput"),
        FSRSError::InvalidDeckSize => ("java/lang/IllegalArgumentException", "InvalidDeckSize"),
    }
}

fn build_fsrs(parameters: &[f32]) -> Result<FSRS, FSRSError> {
    FSRS::new(parameters)
}

fn build_items(
    offsets: &[i32],
    ratings: &[i32],
    deltas: &[i64],
) -> Result<Vec<FSRSItem>, String> {
    if offsets.is_empty() || offsets[0] != 0 {
        return Err("InvalidInput".to_string());
    }
    if offsets.len() < 2 || ratings.len() != deltas.len() {
        return Err("InvalidInput".to_string());
    }
    let mut items = Vec::with_capacity(offsets.len() - 1);
    for pair in offsets.windows(2) {
        let start = pair[0];
        let end = pair[1];
        if start < 0 || end < start || end as usize > ratings.len() {
            return Err("InvalidInput".to_string());
        }
        let mut reviews = Vec::with_capacity((end - start) as usize);
        for index in start..end {
            let rating = ratings[index as usize];
            let delta = deltas[index as usize];
            if !(1..=4).contains(&rating) || delta < 0 || delta > u32::MAX as i64 {
                return Err("InvalidInput".to_string());
            }
            reviews.push(FSRSReview {
                rating: rating as u32,
                delta_t: delta as u32,
            });
        }
        items.push(FSRSItem { reviews });
    }
    if offsets[offsets.len() - 1] as usize != ratings.len() {
        return Err("InvalidInput".to_string());
    }
    Ok(items)
}

fn token(ptr: jlong) -> Result<Option<SharedProgress>, String> {
    if ptr == 0 {
        return Ok(None);
    }
    if ptr < 0 {
        return Err("InvalidInput".to_string());
    }
    let registry = registry().lock().map_err(|_| "NativeFailure".to_string())?;
    registry
        .tokens
        .get(&(ptr as u64))
        .cloned()
        .map(Some)
        .ok_or_else(|| "InvalidInput".to_string())
}

fn next_states(
    env: &mut JNIEnv<'_>,
    parameters: JFloatArray,
    has_memory: jboolean,
    stability: jfloat,
    difficulty: jfloat,
    desired_retention: jfloat,
    days_elapsed: jlong,
) -> jfloatArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<f32>, String> {
        let params = read_floats(env, &parameters)?;
        let fsrs = build_fsrs(&params).map_err(|error| map_error(error).1.to_string())?;
        let memory = (has_memory == JNI_TRUE).then_some(MemoryState {
            stability,
            difficulty,
        });
        let days = days_elapsed.clamp(0, u32::MAX as i64) as u32;
        let states = fsrs
            .next_states(memory, desired_retention, days)
            .map_err(|error| map_error(error).1.to_string())?;
        let mut out = Vec::with_capacity(12);
        for state in [states.again, states.hard, states.good, states.easy] {
            out.push(state.memory.stability);
            out.push(state.memory.difficulty);
            out.push(state.interval);
        }
        Ok(out)
    }));
    match result {
        Ok(Ok(values)) => match floats(env, &values) {
            Ok(array) => array,
            Err(message) => {
                throw(env, "java/lang/IllegalStateException", &message);
                std::ptr::null_mut()
            }
        },
        Ok(Err(message)) => {
            throw(env, "java/lang/IllegalArgumentException", &message);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(env, "java/lang/IllegalStateException", "NativePanic");
            std::ptr::null_mut()
        }
    }
}

fn memory_state(
    env: &mut JNIEnv<'_>,
    parameters: JFloatArray,
    ratings: JIntArray,
    deltas: JLongArray,
    has_starting: jboolean,
    stability: jfloat,
    difficulty: jfloat,
) -> jfloatArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<f32>, String> {
        let params = read_floats(env, &parameters)?;
        let ratings = read_ints(env, &ratings)?;
        let deltas = read_longs(env, &deltas)?;
        if ratings.len() != deltas.len() {
            return Err("InvalidInput".to_string());
        }
        let mut reviews = Vec::with_capacity(ratings.len());
        for (rating, delta) in ratings.into_iter().zip(deltas) {
            if !(1..=4).contains(&rating) || delta < 0 || delta > u32::MAX as i64 {
                return Err("InvalidInput".to_string());
            }
            reviews.push(FSRSReview {
                rating: rating as u32,
                delta_t: delta as u32,
            });
        }
        let fsrs = build_fsrs(&params).map_err(|error| map_error(error).1.to_string())?;
        let starting = (has_starting == JNI_TRUE).then_some(MemoryState {
            stability,
            difficulty,
        });
        let memory = fsrs
            .memory_state(FSRSItem { reviews }, starting)
            .map_err(|error| map_error(error).1.to_string())?;
        Ok(vec![memory.stability, memory.difficulty])
    }));
    match result {
        Ok(Ok(values)) => match floats(env, &values) {
            Ok(array) => array,
            Err(message) => {
                throw(env, "java/lang/IllegalStateException", &message);
                std::ptr::null_mut()
            }
        },
        Ok(Err(message)) => {
            throw(env, "java/lang/IllegalArgumentException", &message);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(env, "java/lang/IllegalStateException", "NativePanic");
            std::ptr::null_mut()
        }
    }
}

fn evaluate(
    env: &mut JNIEnv<'_>,
    parameters: JFloatArray,
    offsets: JIntArray,
    ratings: JIntArray,
    deltas: JLongArray,
    cancel_token: jlong,
) -> jfloatArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<f32>, String> {
        let params = read_floats(env, &parameters)?;
        let offsets = read_ints(env, &offsets)?;
        let ratings = read_ints(env, &ratings)?;
        let deltas = read_longs(env, &deltas)?;
        let items = build_items(&offsets, &ratings, &deltas)?;
        let fsrs = build_fsrs(&params).map_err(|error| map_error(error).1.to_string())?;
        let progress = token(cancel_token)?;
        let evaluation = fsrs
            .evaluate(items, |_| {
                progress
                    .as_ref()
                    .map(|shared| !shared.lock().unwrap().want_abort)
                    .unwrap_or(true)
            })
            .map_err(|error| map_error(error).1.to_string())?;
        Ok(vec![evaluation.log_loss, evaluation.rmse_bins])
    }));
    match result {
        Ok(Ok(values)) => match floats(env, &values) {
            Ok(array) => array,
            Err(message) => {
                throw(env, "java/lang/IllegalStateException", &message);
                std::ptr::null_mut()
            }
        },
        Ok(Err(message)) => {
            throw(env, "java/lang/IllegalArgumentException", &message);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(env, "java/lang/IllegalStateException", "NativePanic");
            std::ptr::null_mut()
        }
    }
}

fn optimize(
    env: &mut JNIEnv<'_>,
    offsets: JIntArray,
    ratings: JIntArray,
    deltas: JLongArray,
    card_ids: JLongArray,
    enable_short_term: jboolean,
    num_relearning_steps: jint,
    cancel_token: jlong,
) -> jfloatArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<f32>, String> {
        let offsets = read_ints(env, &offsets)?;
        let ratings = read_ints(env, &ratings)?;
        let deltas = read_longs(env, &deltas)?;
        let items = build_items(&offsets, &ratings, &deltas)?;
        let ids = read_longs(env, &card_ids)?;
        let card_ids = (!ids.is_empty()).then_some(ids);
        let progress = token(cancel_token)?;
        let params = compute_parameters(ComputeParametersInput {
            train_set: items,
            card_ids,
            progress,
            enable_short_term: enable_short_term == JNI_TRUE,
            num_relearning_steps: (num_relearning_steps >= 0)
                .then_some(num_relearning_steps as usize),
            training_config: None,
        })
        .map_err(|error| map_error(error).1.to_string())?;
        Ok(params)
    }));
    match result {
        Ok(Ok(values)) => match floats(env, &values) {
            Ok(array) => array,
            Err(message) => {
                throw(env, "java/lang/IllegalStateException", &message);
                std::ptr::null_mut()
            }
        },
        Ok(Err(message)) => {
            let class = if message == "Interrupted" {
                "java/lang/IllegalStateException"
            } else {
                "java/lang/IllegalArgumentException"
            };
            throw(env, class, &message);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(env, "java/lang/IllegalStateException", "NativePanic");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_deepstudent_engines_fsrs_FsrsNative_nextStates(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    parameters: JFloatArray,
    has_memory: jboolean,
    stability: jfloat,
    difficulty: jfloat,
    desired_retention: jfloat,
    days_elapsed: jlong,
) -> jfloatArray {
    next_states(
        &mut env,
        parameters,
        has_memory,
        stability,
        difficulty,
        desired_retention,
        days_elapsed,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_deepstudent_engines_fsrs_FsrsNative_memoryState(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    parameters: JFloatArray,
    ratings: JIntArray,
    deltas: JLongArray,
    has_starting: jboolean,
    stability: jfloat,
    difficulty: jfloat,
) -> jfloatArray {
    memory_state(
        &mut env,
        parameters,
        ratings,
        deltas,
        has_starting,
        stability,
        difficulty,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_deepstudent_engines_fsrs_FsrsNative_evaluate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    parameters: JFloatArray,
    offsets: JIntArray,
    ratings: JIntArray,
    deltas: JLongArray,
    cancel_token: jlong,
) -> jfloatArray {
    evaluate(
        &mut env,
        parameters,
        offsets,
        ratings,
        deltas,
        cancel_token,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_deepstudent_engines_fsrs_FsrsNative_optimize(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    offsets: JIntArray,
    ratings: JIntArray,
    deltas: JLongArray,
    card_ids: JLongArray,
    enable_short_term: jboolean,
    num_relearning_steps: jint,
    cancel_token: jlong,
) -> jfloatArray {
    optimize(
        &mut env,
        offsets,
        ratings,
        deltas,
        card_ids,
        enable_short_term,
        num_relearning_steps,
        cancel_token,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_deepstudent_engines_fsrs_FsrsNative_createCancelToken(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut registry = registry().lock().map_err(|_| "NativeFailure")?;
        let id = registry.next;
        registry.next = registry.next.checked_add(1).ok_or("NativeFailure")?;
        registry
            .tokens
            .insert(id, CombinedProgressState::new_shared());
        Ok::<jlong, &'static str>(id as jlong)
    }));
    match result {
        Ok(Ok(pointer)) => pointer,
        Ok(Err(message)) => {
            throw(&mut env, "java/lang/IllegalStateException", message);
            0
        }
        Err(_) => {
            throw(&mut env, "java/lang/IllegalStateException", "NativePanic");
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_deepstudent_engines_fsrs_FsrsNative_cancelToken(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    pointer: jlong,
) {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if pointer > 0 {
            let registry = registry().lock().map_err(|_| "NativeFailure")?;
            if let Some(shared) = registry.tokens.get(&(pointer as u64)) {
                shared.lock().map_err(|_| "NativeFailure")?.want_abort = true;
            }
        }
        Ok::<(), &'static str>(())
    }));
    match result {
        Ok(Ok(())) => {}
        Ok(Err(message)) => throw(&mut env, "java/lang/IllegalStateException", message),
        Err(_) => throw(&mut env, "java/lang/IllegalStateException", "NativePanic"),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_deepstudent_engines_fsrs_FsrsNative_destroyCancelToken(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    pointer: jlong,
) {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if pointer > 0 {
            let mut registry = registry().lock().map_err(|_| "NativeFailure")?;
            if let Some(shared) = registry.tokens.remove(&(pointer as u64)) {
                shared.lock().map_err(|_| "NativeFailure")?.want_abort = true;
            }
        }
        Ok::<(), &'static str>(())
    }));
    match result {
        Ok(Ok(())) => {}
        Ok(Err(message)) => throw(&mut env, "java/lang/IllegalStateException", message),
        Err(_) => throw(&mut env, "java/lang/IllegalStateException", "NativePanic"),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_deepstudent_engines_fsrs_FsrsNative_normalizeParameters(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    parameters: JFloatArray,
) -> jfloatArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<f32>, String> {
        let params = read_floats(&mut env, &parameters)?;
        check_and_fill_parameters(&params).map_err(|error| map_error(error).1.to_string())
    }));
    match result {
        Ok(Ok(values)) => match floats(&mut env, &values) {
            Ok(array) => array,
            Err(message) => {
                throw(&mut env, "java/lang/IllegalStateException", &message);
                std::ptr::null_mut()
            }
        },
        Ok(Err(message)) => {
            throw(&mut env, "java/lang/IllegalArgumentException", &message);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(&mut env, "java/lang/IllegalStateException", "NativePanic");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_deepstudent_engines_fsrs_FsrsNative_version(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    match strings(&mut env, "fsrs-6.6.2") {
        Ok(value) => value,
        Err(message) => {
            throw(&mut env, "java/lang/IllegalStateException", &message);
            std::ptr::null_mut()
        }
    }
}

pub fn jni_version() -> &'static str {
    "fsrs-6.6.2"
}
