import { TrainingPlanCard } from "./TrainingPlanCard";
import { DietRecommendCard } from "./DietRecommendCard";
import type { TrainingPlan, DietRecommendation } from "./types";

const MOCK_TRAINING_PLAN: TrainingPlan = {
  id: "plan-1",
  name: "全身燃脂挑战",
  goal: "fat_loss",
  totalXP: 150,
  streak: 12,
  progress: 0,
  exercises: [
    { id: "ex-1", name: "热身慢跑", sets: 1, reps: 1, duration: 10, completed: true, icon: "🏃", category: "cardio" },
    { id: "ex-2", name: "深蹲", sets: 4, reps: 15, weight: 40, completed: true, icon: "🦵", category: "strength" },
    { id: "ex-3", name: "平板支撑", sets: 3, reps: 1, duration: 60, completed: false, icon: "🧘", category: "core" },
    { id: "ex-4", name: "哑铃卧推", sets: 3, reps: 12, weight: 20, completed: false, icon: "💪", category: "strength" },
    { id: "ex-5", name: "开合跳", sets: 3, reps: 30, completed: false, icon: "⭐", category: "cardio" },
    { id: "ex-6", name: "拉伸放松", sets: 1, reps: 1, duration: 5, completed: false, icon: "🤸", category: "flexibility" },
  ],
};

const MOCK_DIET_RECOMMENDATION: DietRecommendation = {
  meals: [
    { id: "m-1", name: "全麦面包 + 水煮蛋 + 牛奶", calories: 420, protein: 28, carbs: 45, fat: 15, icon: "🥪", mealType: "breakfast" },
    { id: "m-2", name: "鸡胸肉沙拉 + 糙米饭", calories: 550, protein: 42, carbs: 55, fat: 12, icon: "🥗", mealType: "lunch" },
    { id: "m-3", name: "三文鱼 + 西兰花 + 红薯", calories: 480, protein: 35, carbs: 40, fat: 18, icon: "🐟", mealType: "dinner" },
    { id: "m-4", name: "希腊酸奶 + 混合坚果", calories: 200, protein: 15, carbs: 12, fat: 10, icon: "🥜", mealType: "snack" },
  ],
  nutrition: {
    calories: 1650,
    caloriesGoal: 2200,
    protein: 120,
    proteinGoal: 150,
    carbs: 152,
    carbsGoal: 250,
    fat: 55,
    fatGoal: 70,
  },
  waterIntake: 4,
  waterGoal: 8,
};

interface FitTrackWidgetPanelProps {
  onClose?: () => void;
}

export function FitTrackWidgetPanel({ onClose }: FitTrackWidgetPanelProps) {
  return (
    <div className="duo-widget-panel">
      {/* 面板头部 */}
      <div className="duo-panel-header">
        <div className="duo-panel-brand">
          <span className="duo-brand-owl">🐱</span>
          <div>
            <h2 className="duo-panel-title">FitTrack</h2>
            <p className="duo-panel-subtitle">今日健康面板</p>
          </div>
        </div>
        {onClose && (
          <button className="duo-close-btn" onClick={onClose} aria-label="关闭面板">
            ✕
          </button>
        )}
      </div>

      {/* 小部件内容区 */}
      <div className="duo-panel-content">
        <TrainingPlanCard
          plan={MOCK_TRAINING_PLAN}
          onComplete={(id) => console.log("Exercise completed:", id)}
          onStartWorkout={() => console.log("Workout started")}
        />
        <DietRecommendCard recommendation={MOCK_DIET_RECOMMENDATION} />
      </div>

      {/* 底部信息 */}
      <div className="duo-panel-footer">
        <span>数据更新于 12:30</span>
        <span className="duo-footer-dot">·</span>
        <span>由 AI 营养师生成</span>
      </div>
    </div>
  );
}
