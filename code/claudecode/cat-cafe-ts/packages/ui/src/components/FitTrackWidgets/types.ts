export interface Exercise {
  id: string;
  name: string;
  sets: number;
  reps: number;
  weight?: number;
  duration?: number;
  completed: boolean;
  icon: string;
  category: "strength" | "cardio" | "flexibility" | "core";
}

export interface TrainingPlan {
  id: string;
  name: string;
  goal: "general" | "muscle_gain" | "fat_loss" | "strength" | "endurance";
  exercises: Exercise[];
  totalXP: number;
  streak: number;
  progress: number;
}

export interface MealItem {
  id: string;
  name: string;
  calories: number;
  protein: number;
  carbs: number;
  fat: number;
  icon: string;
  mealType: "breakfast" | "lunch" | "dinner" | "snack";
}

export interface NutritionSummary {
  calories: number;
  caloriesGoal: number;
  protein: number;
  proteinGoal: number;
  carbs: number;
  carbsGoal: number;
  fat: number;
  fatGoal: number;
}

export interface DietRecommendation {
  meals: MealItem[];
  nutrition: NutritionSummary;
  waterIntake: number;
  waterGoal: number;
}

export const GOAL_LABELS: Record<TrainingPlan["goal"], string> = {
  general: "综合体能",
  muscle_gain: "增肌塑形",
  fat_loss: "减脂燃脂",
  strength: "力量提升",
  endurance: "耐力训练",
};

export const MEAL_LABELS: Record<MealItem["mealType"], string> = {
  breakfast: "早餐",
  lunch: "午餐",
  dinner: "晚餐",
  snack: "加餐",
};

export const CATEGORY_COLORS: Record<Exercise["category"], string> = {
  strength: "#CE82FF",
  cardio: "#FF4B4B",
  flexibility: "#1CB0F6",
  core: "#FFC800",
};

export const CATEGORY_BG_COLORS: Record<Exercise["category"], string> = {
  strength: "rgba(206, 130, 255, 0.15)",
  cardio: "rgba(255, 75, 75, 0.15)",
  flexibility: "rgba(28, 176, 246, 0.15)",
  core: "rgba(255, 200, 0, 0.15)",
};
